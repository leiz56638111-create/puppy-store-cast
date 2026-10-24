package com.airtv.receiver.airplay

import java.nio.ByteBuffer

/**
 * Callbacks invoked by the native AirPlay server. Every method is called from a native
 * thread (RTSP, RTP or mirror), never the main thread.
 *
 * The [ByteBuffer]s handed to [onVideoData] / [onAudioData] wrap native memory that is
 * only valid for the duration of the call: copy anything you need to keep.
 *
 * Method names and signatures are looked up by JNI in `airplay_jni.c` — keep them in sync.
 */
interface AirPlayCallbacks {
    fun onLog(level: Int, message: String)

    /** Return true to admit the client. */
    fun onClientConnect(name: String, model: String, deviceId: String): Boolean

    fun onSessionStart()
    fun onSessionEnd()

    /** Return 0 to accept the codec, -1 to reject the stream. */
    fun onVideoCodec(isH265: Boolean): Int

    fun onVideoData(data: ByteBuffer, length: Int, presentationTimeUs: Long)
    fun onVideoSize(width: Int, height: Int)
    fun onVideoFlush()
    fun onVideoPause()
    fun onVideoResume()
    fun onVideoStop()

    /**
     * @param contentType the AirPlay audio "ct": 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD.
     * @param usingScreen true for a screen-mirroring session, false for audio-only AirPlay
     * (where the sender keeps the video on its own display).
     */
    fun onAudioFormat(contentType: Int, usingScreen: Boolean, isMedia: Boolean)

    fun onAudioData(data: ByteBuffer, length: Int, presentationTimeUs: Long)
    fun onAudioFlush()

    /** AirPlay volume in dB: -30 (quietest) to 0 (loudest), -144 means muted. */
    fun onVolume(decibels: Float)
}

/** Thin wrapper over the native AirPlay server. */
object NativeAirPlay {

    const val LOG_ERROR = 3
    const val LOG_WARNING = 4
    const val LOG_INFO = 6
    const val LOG_DEBUG = 7

    init {
        System.loadLibrary("airplayjni")
    }

    /**
     * Starts the RTSP server and publishes the `_airplay._tcp` / `_raop._tcp` services.
     * Returns the listening port, or a negative error code.
     */
    @JvmStatic
    external fun nativeStart(
        name: String,
        macAddress: String,
        keyFilePath: String,
        width: Int,
        height: Int,
        fps: Int,
        logLevel: Int,
        callbacks: AirPlayCallbacks,
    ): Int

    @JvmStatic
    external fun nativeStop()

    @JvmStatic
    external fun nativeIsRunning(): Boolean
}
