/*
 * JNI bridge between the vendored AirPlay server library (UxPlay's lib/, LGPL/GPL)
 * and the Android app. Native threads push H.264/AAC payloads up to Kotlin, which
 * renders them with MediaCodec / AudioTrack.
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>

#include "dnssd.h"
#include "global.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

#define TAG "AirPlayNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static pthread_key_t g_tls_key;

static jobject g_cb = NULL; /* global ref to the Kotlin AirPlayCallbacks object */
static raop_t *g_raop = NULL;
static dnssd_t *g_dnssd = NULL;
static unsigned short g_port = 0;
static int g_video_enabled = 0;
static pthread_mutex_t g_video_lock = PTHREAD_MUTEX_INITIALIZER;
static playback_info_t g_video_info;
static pthread_cond_t g_video_ready_cond=PTHREAD_COND_INITIALIZER;
static int g_video_shutdown=0;
static jlong g_video_ticket = 0;
static double g_video_buffered = 0;
static jlong video_ticket(void) {
    pthread_mutex_lock(&g_video_lock); jlong value = g_video_ticket; pthread_mutex_unlock(&g_video_lock); return value;
}

/* clock: AirPlay timestamps are remote NTP nanoseconds; normalise to a session base */
static uint64_t g_clock_offset = 0;
static uint64_t g_pts_base = 0;
static int g_pts_base_set = 0;
static pthread_mutex_t g_clock_lock = PTHREAD_MUTEX_INITIALIZER;

static struct {
    jmethodID onLog;
    jmethodID onClientConnect;
    jmethodID onSessionStart;
    jmethodID onSessionEnd;
    jmethodID onVideoCodec;
    jmethodID onVideoData;
    jmethodID onVideoSize;
    jmethodID onVideoFlush;
    jmethodID onVideoPause;
    jmethodID onVideoResume;
    jmethodID onVideoStop;
    jmethodID onAudioFormat;
    jmethodID onAudioData;
    jmethodID onAudioFlush;
    jmethodID onVolume;
    jmethodID onRemoteVideoPlay, onRemoteVideoSeek, onRemoteVideoRate, onRemoteVideoStop;
} M;

static void detach_current_thread(void *value) {
    (void) value;
    if (g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

/* Native library threads (RTSP, RTP, mirror, NTP) need a JNIEnv; attach lazily and
 * detach through a TLS destructor when the thread dies. */
static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
        LOGE("AttachCurrentThread failed");
        return NULL;
    }
    pthread_setspecific(g_tls_key, (void *) 1);
    return env;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* Convert a remote NTP timestamp (ns) to a session-relative presentation time (us). */
static int64_t to_pts_us(uint64_t ntp_remote, uint64_t ntp_local) {
    pthread_mutex_lock(&g_clock_lock);
    if (!g_clock_offset) {
        uint64_t local = ntp_local ? ntp_local : get_local_time();
        g_clock_offset = local - ntp_remote;
    }
    uint64_t local_ns = ntp_remote + g_clock_offset;
    if (!g_pts_base_set) {
        g_pts_base = local_ns;
        g_pts_base_set = 1;
    }
    int64_t pts = (int64_t) (local_ns - g_pts_base) / 1000;
    pthread_mutex_unlock(&g_clock_lock);
    return pts < 0 ? 0 : pts;
}

static void reset_clock(void) {
    pthread_mutex_lock(&g_clock_lock);
    g_clock_offset = 0;
    g_pts_base = 0;
    g_pts_base_set = 0;
    pthread_mutex_unlock(&g_clock_lock);
}

/* ------------------------------------------------------------------ callbacks */

static void cb_log(void *cls, int level, const char *msg) {
    (void) cls;
    if(g_video_enabled && msg && (strstr(msg,"http://") || strstr(msg,"https://") || strstr(msg,"Authorization"))) return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case LOGGER_ERR: prio = ANDROID_LOG_ERROR; break;
        case LOGGER_WARNING: prio = ANDROID_LOG_WARN; break;
        case LOGGER_INFO: prio = ANDROID_LOG_INFO; break;
        default: prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, "AirPlayLib", "%s", msg);

    if (!g_cb || !M.onLog) return;
    JNIEnv *env = get_env();
    if (!env) return;
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "");
    (*env)->CallVoidMethod(env, g_cb, M.onLog, (jint) level, jmsg);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jmsg);
}

static void cb_conn_init(void *cls) {
    (void) cls;
    reset_clock();
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onSessionStart);
    clear_exception(env);
}

static void cb_conn_destroy(void *cls) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onSessionEnd);
    clear_exception(env);
    reset_clock();
}

static void cb_conn_reset(void *cls, int reason) {
    (void) cls;
    LOGI("conn_reset reason=%d", reason);
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onVideoStop);
    clear_exception(env);
    reset_clock();
}

static void cb_conn_feedback(void *cls) { (void) cls; }

static void cb_video_reset(void *cls, reset_type_t reset_type) {
    (void) cls;
    LOGI("video_reset type=%d", (int) reset_type);
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onVideoStop);
    clear_exception(env);
    reset_clock();
}

static int cb_video_set_codec(void *cls, video_codec_t codec) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return -1;
    jint r = (*env)->CallIntMethod(env, g_cb, M.onVideoCodec,
                                   (jboolean) (codec == VIDEO_CODEC_H265));
    clear_exception(env);
    return (int) r;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls;
    (void) ntp;
    if (!data || !data->data || data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    int64_t pts = to_pts_us(data->ntp_time_remote, data->ntp_time_local);
    jobject buf = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
    if (buf) {
        (*env)->CallVoidMethod(env, g_cb, M.onVideoData, buf, (jint) data->data_len,
                               (jlong) pts);
        clear_exception(env);
        (*env)->DeleteLocalRef(env, buf);
    }
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls;
    (void) ntp;
    if (!data || !data->data || data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    int64_t pts = to_pts_us(data->ntp_time_remote, data->ntp_time_local);
    jobject buf = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
    if (buf) {
        (*env)->CallVoidMethod(env, g_cb, M.onAudioData, buf, (jint) data->data_len,
                               (jlong) pts);
        clear_exception(env);
        (*env)->DeleteLocalRef(env, buf);
    }
}

static void call_void(jmethodID m) {
    JNIEnv *env = get_env();
    if (!env || !g_cb || !m) return;
    (*env)->CallVoidMethod(env, g_cb, m);
    clear_exception(env);
}

static void cb_video_flush(void *cls) { (void) cls; call_void(M.onVideoFlush); }
static void cb_audio_flush(void *cls) { (void) cls; call_void(M.onAudioFlush); }
static void cb_video_pause(void *cls) { (void) cls; call_void(M.onVideoPause); }
static void cb_video_resume(void *cls) { (void) cls; call_void(M.onVideoResume); }

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                                bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void) cls;
    (void) spf;
    (void) audioFormat;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    /* usingScreen distinguishes a mirroring session from audio-only AirPlay. */
    (*env)->CallVoidMethod(env, g_cb, M.onAudioFormat, (jint) (ct ? *ct : 0),
                           (jboolean) (usingScreen && *usingScreen),
                           (jboolean) (isMedia && *isMedia));
    clear_exception(env);
}

static void cb_video_report_size(void *cls, float *width_source, float *height_source,
                                 float *width, float *height) {
    (void) cls;
    (void) width;
    (void) height;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onVideoSize,
                           (jint) (width_source ? *width_source : 0),
                           (jint) (height_source ? *height_source : 0));
    clear_exception(env);
}

static double cb_audio_set_client_volume(void *cls) {
    (void) cls;
    return 0.0; /* 0 dB attenuation: the TV handles final volume */
}

static void cb_audio_set_volume(void *cls, float volume) {
    (void) cls;
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, M.onVolume, (jfloat) volume);
    clear_exception(env);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name,
                                    bool *admit) {
    (void) cls;
    if (admit) *admit = true;
    LOGI("client request: name=%s model=%s id=%s", name ? name : "?", model ? model : "?",
         deviceid ? deviceid : "?");
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    jstring jname = (*env)->NewStringUTF(env, name ? name : "");
    jstring jmodel = (*env)->NewStringUTF(env, model ? model : "");
    jstring jid = (*env)->NewStringUTF(env, deviceid ? deviceid : "");
    jboolean ok = (*env)->CallBooleanMethod(env, g_cb, M.onClientConnect, jname, jmodel, jid);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jmodel);
    (*env)->DeleteLocalRef(env, jid);
    if (admit) *admit = (ok == JNI_TRUE);
}

/* Direct-video bridge. The native network thread reads a pushed snapshot; it never
 * waits for Android's main thread while answering playback-info. */
static void cb_on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls;
    if (!g_video_enabled || !location) return;
    pthread_mutex_lock(&g_video_lock);
    jlong ticket = ++g_video_ticket;
    memset(&g_video_info, 0, sizeof(g_video_info));
    g_video_info.position = start_position; g_video_buffered = 0;
    pthread_mutex_unlock(&g_video_lock);
    JNIEnv *env = get_env(); if (!env || !g_cb) return;
    jstring url = (*env)->NewStringUTF(env, location);
    (*env)->CallVoidMethod(env, g_cb, M.onRemoteVideoPlay, url, (jfloat) start_position, ticket);
    (*env)->DeleteLocalRef(env, url); clear_exception(env);
}
static void cb_on_video_wait_ready(void *cls) {
    (void)cls;if(!g_video_enabled)return;
    struct timespec until;clock_gettime(CLOCK_REALTIME,&until);until.tv_sec+=5;
    pthread_mutex_lock(&g_video_lock);
    while(!g_video_info.ready_to_play && !g_video_shutdown) {
        if(pthread_cond_timedwait(&g_video_ready_cond,&g_video_lock,&until)==ETIMEDOUT)break;
    }
    pthread_mutex_unlock(&g_video_lock);
}
static void cb_on_video_scrub(void *cls, const float position) {
    (void) cls; JNIEnv *env = get_env();
    if (g_video_enabled && env && g_cb) { (*env)->CallVoidMethod(env, g_cb, M.onRemoteVideoSeek, position, video_ticket()); clear_exception(env); }
}
static void cb_on_video_rate(void *cls, const float rate) {
    (void) cls; JNIEnv *env = get_env();
    if (g_video_enabled && env && g_cb) { (*env)->CallVoidMethod(env, g_cb, M.onRemoteVideoRate, rate, video_ticket()); clear_exception(env); }
}
static void cb_on_video_stop(void *cls) {
    (void) cls;
    if (!g_video_enabled) { call_void(M.onVideoStop); return; }
    JNIEnv *env = get_env();
    jlong ticket = video_ticket();
    if (env && g_cb) { (*env)->CallVoidMethod(env, g_cb, M.onRemoteVideoStop, ticket); clear_exception(env); }
}
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void) cls; if (!info) return;
    pthread_mutex_lock(&g_video_lock); *info = g_video_info; pthread_mutex_unlock(&g_video_lock);
}
static float cb_on_video_playlist_remove(void *cls) {
    (void) cls; pthread_mutex_lock(&g_video_lock); float value = (float)g_video_info.position; pthread_mutex_unlock(&g_video_lock); return value;
}

/* ------------------------------------------------------------------ JNI entry */

static int cache_methods(JNIEnv *env, jobject cb) {
    jclass c = (*env)->GetObjectClass(env, cb);
    if (!c) return -1;
#define GET(field, name, sig)                                     \
    M.field = (*env)->GetMethodID(env, c, name, sig);             \
    if (!M.field) {                                               \
        LOGE("missing callback method %s%s", name, sig);           \
        return -1;                                                \
    }
    GET(onLog, "onLog", "(ILjava/lang/String;)V")
    GET(onClientConnect, "onClientConnect",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z")
    GET(onSessionStart, "onSessionStart", "()V")
    GET(onSessionEnd, "onSessionEnd", "()V")
    GET(onVideoCodec, "onVideoCodec", "(Z)I")
    GET(onVideoData, "onVideoData", "(Ljava/nio/ByteBuffer;IJ)V")
    GET(onVideoSize, "onVideoSize", "(II)V")
    GET(onVideoFlush, "onVideoFlush", "()V")
    GET(onVideoPause, "onVideoPause", "()V")
    GET(onVideoResume, "onVideoResume", "()V")
    GET(onVideoStop, "onVideoStop", "()V")
    GET(onAudioFormat, "onAudioFormat", "(IZZ)V")
    GET(onAudioData, "onAudioData", "(Ljava/nio/ByteBuffer;IJ)V")
    GET(onAudioFlush, "onAudioFlush", "()V")
    GET(onVolume, "onVolume", "(F)V")
    GET(onRemoteVideoPlay, "onRemoteVideoPlay", "(Ljava/lang/String;FJ)V")
    GET(onRemoteVideoSeek, "onRemoteVideoSeek", "(FJ)V")
    GET(onRemoteVideoRate, "onRemoteVideoRate", "(FJ)V")
    GET(onRemoteVideoStop, "onRemoteVideoStop", "(J)V")
#undef GET
    (*env)->DeleteLocalRef(env, c);
    return 0;
}

static void set_airplay_features(dnssd_t *dnssd) {
    if (g_video_enabled) {
        // Video and HLS, no photo, screen-mirror, or protected-video capability promise.
        const int bits[] = {0,4,9,12,14,16,17,18,19,20,21,22,25,27,28,30};
        for(int i=0;i<64;i++) dnssd_set_airplay_features(dnssd,i,0);
        for(size_t i=0;i<sizeof(bits)/sizeof(bits[0]);i++)dnssd_set_airplay_features(dnssd,bits[i],1);
        return;
    }
    /* Mirroring + audio receiver with FairPlay auth; AirPlay-video/HLS is off. */
    static const int bits_on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15,
                                  16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
    for (int i = 0; i < 64; i++) {
        dnssd_set_airplay_features(dnssd, i, 0);
    }
    for (size_t i = 0; i < sizeof(bits_on) / sizeof(bits_on[0]); i++) {
        dnssd_set_airplay_features(dnssd, bits_on[i], 1);
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_tls_key, detach_current_thread);
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_airtv_receiver_airplay_NativeAirPlay_nativeStart(
        JNIEnv *env, jclass clazz, jstring jname, jstring jmac, jstring jkeyfile,
        jint width, jint height, jint fps, jint logLevel, jobject callbacks) {
    (void) clazz;
    if (g_raop) {
        LOGE("server already running");
        return -100;
    }

    if (cache_methods(env, callbacks) != 0) {
        return -101;
    }
    g_cb = (*env)->NewGlobalRef(env, callbacks);

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);

    int result = 0;
    unsigned char hwaddr[MAX_HWADDR_LEN] = {0};
    /* parse "aa:bb:cc:dd:ee:ff" */
    {
        unsigned int b[MAX_HWADDR_LEN] = {0};
        if (sscanf(mac, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) {
            LOGE("bad mac address '%s'", mac);
            result = -102;
            goto done;
        }
        for (int i = 0; i < MAX_HWADDR_LEN; i++) hwaddr[i] = (unsigned char) b[i];
    }

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.report_client_request = cb_report_client_request;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_wait_ready = cb_on_video_wait_ready;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        result = -1;
        goto done;
    }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, logLevel);

    if (raop_init2(g_raop, 1 /* nohold */, mac, keyfile)) {
        LOGE("raop_init2 failed");
        free(g_raop);
        g_raop = NULL;
        result = -2;
        goto done;
    }

    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", fps);
    raop_set_plist(g_raop, "maxFPS", fps);
    raop_set_plist(g_raop, "overscanned", 0);
    raop_set_plist(g_raop, "hls", g_video_enabled);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    g_port = raop_get_port(g_raop);
    /* httpd_start() returns 1 when it starts the daemon, 0 if it was already running,
     * and a negative value on error. */
    if (raop_start_httpd(g_raop, &g_port) < 0) {
        LOGE("raop_start_httpd failed");
        raop_destroy(g_raop);
        g_raop = NULL;
        result = -3;
        goto done;
    }
    raop_set_port(g_raop, g_port);

    int dnssd_error = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), (const char *) hwaddr, MAX_HWADDR_LEN,
                         0 /* pin_pw */, &dnssd_error);
    if (!g_dnssd) {
        LOGE("dnssd_init failed: %d", dnssd_error);
        raop_destroy(g_raop);
        g_raop = NULL;
        result = -4;
        goto done;
    }
    set_airplay_features(g_dnssd);
    raop_set_dnssd(g_raop, g_dnssd);

    dnssd_error = dnssd_register_raop(g_dnssd, g_port);
    if (dnssd_error) {
        LOGE("dnssd_register_raop failed: %d", dnssd_error);
        result = -5;
        goto fail_dnssd;
    }
    dnssd_error = dnssd_register_airplay(g_dnssd, g_port);
    if (dnssd_error) {
        LOGE("dnssd_register_airplay failed: %d", dnssd_error);
        dnssd_unregister_raop(g_dnssd);
        result = -6;
        goto fail_dnssd;
    }

    LOGI("AirPlay server '%s' listening on port %d, features 0x%llx", name, (int) g_port,
         (unsigned long long) dnssd_get_airplay_features(g_dnssd));
    result = (int) g_port;
    goto done;

fail_dnssd:
    dnssd_destroy(g_dnssd);
    g_dnssd = NULL;
    raop_destroy(g_raop);
    g_raop = NULL;

done:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    if (result < 0 && g_cb) {
        (*env)->DeleteGlobalRef(env, g_cb);
        g_cb = NULL;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_airtv_receiver_airplay_NativeAirPlay_nativeStop(JNIEnv *env, jclass clazz) {
    (void) clazz;
    pthread_mutex_lock(&g_video_lock);g_video_shutdown=1;pthread_cond_broadcast(&g_video_ready_cond);pthread_mutex_unlock(&g_video_lock);
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    if (g_cb) {
        (*env)->DeleteGlobalRef(env, g_cb);
        g_cb = NULL;
    }
    reset_clock();
    g_port = 0;
    LOGI("AirPlay server stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_airtv_receiver_airplay_NativeAirPlay_nativeIsRunning(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return g_raop != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_airtv_receiver_airplay_NativeAirPlay_nativeConfigureVideo(JNIEnv *env, jclass clazz, jboolean enabled) {
    (void)env;(void)clazz;
    if(g_raop) return;
    g_video_enabled = enabled == JNI_TRUE;g_video_shutdown=0;
    pthread_mutex_lock(&g_video_lock); ++g_video_ticket; memset(&g_video_info,0,sizeof(g_video_info));g_video_buffered=0;pthread_mutex_unlock(&g_video_lock);
}
JNIEXPORT void JNICALL
Java_com_airtv_receiver_airplay_NativeAirPlay_nativeUpdateVideoState(JNIEnv *env,jclass clazz,jlong ticket,
        jdouble position,jdouble duration,jdouble buffered,jfloat rate,jboolean ready,jboolean buffering,jboolean seekable) {
    (void)env;(void)clazz;
    pthread_mutex_lock(&g_video_lock);
    if(ticket == g_video_ticket) {
        g_video_info.position=position; g_video_info.duration=duration; g_video_info.buffered=buffered; g_video_info.rate=rate;
        g_video_info.ready_to_play=ready;g_video_info.playback_buffer_empty=buffering;
        g_video_info.playback_buffer_full=!buffering;g_video_info.playback_likely_to_keep_up=ready && !buffering;
        g_video_info.seek_start=0;g_video_info.seek_duration=seekable?duration:0;g_video_buffered=buffered;
        pthread_cond_broadcast(&g_video_ready_cond);
    }
    pthread_mutex_unlock(&g_video_lock);
}
