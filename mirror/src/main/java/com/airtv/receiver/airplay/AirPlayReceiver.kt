package com.airtv.receiver.airplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import com.airtv.receiver.media.AudioPipeline
import com.airtv.receiver.media.VideoPipeline
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

sealed class ReceiverState {
    object Stopped : ReceiverState()
    data class Advertising(val name: String, val port: Int, val address: String?) : ReceiverState()
    data class Streaming(val clientName: String, val width: Int, val height: Int) : ReceiverState()

    /**
     * The sender opened an audio-only AirPlay session (it keeps the picture on its own
     * screen). There is no video stream to show, so say so rather than going black.
     */
    data class AudioOnly(
        val clientName: String,
        val codec: String,
        val playable: Boolean,
    ) : ReceiverState()

    data class Failed(val reason: String) : ReceiverState()
}

/**
 * Owns the native AirPlay server plus the decode pipelines, and translates native
 * callbacks into UI state.
 */
class AirPlayReceiver(private val context: Context) : AirPlayCallbacks {

    private val video = VideoPipeline()
    private val audio = AudioPipeline()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ReceiverState) -> Unit>()

    @Volatile
    var state: ReceiverState = ReceiverState.Stopped
        private set

    @Volatile
    private var clientName: String = ""

    @Volatile
    private var sourceWidth = 0

    @Volatile
    private var sourceHeight = 0

    /** The display geometry advertised to senders. */
    val advertisedDisplay: AdvertisedDisplay by lazy { detectDisplay() }

    init {
        // The decoder is the authority on the picture size the UI must letterbox to; the
        // size the sender announces can disagree with what it actually encodes.
        video.onDecodedSize = { width, height ->
            if (width != sourceWidth || height != sourceHeight) {
                sourceWidth = width
                sourceHeight = height
                publishStreaming()
            }
        }
    }

    val videoFramesRendered: Long get() = video.framesRendered
    val videoFramesDropped: Long get() = video.framesDropped
    @Volatile private var audioRoute = "尚未协商"
    val audioDiagnostics: String get() = "$audioRoute\n${audio.diagnostics()}"
    fun recoverAudio(): Boolean = audio.recover()

    fun addListener(listener: (ReceiverState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (ReceiverState) -> Unit) {
        listeners -= listener
    }

    fun setSurface(surface: Surface?) {
        video.setSurface(surface)
    }

    fun start(): Boolean {
        if (NativeAirPlay.nativeIsRunning()) return true

        val name = DeviceIdentity.serviceName(deviceName(), FALLBACK_NAME)
        val mac = DeviceIdentity.macAddressFrom(deviceSeed())
        val keyFile = File(context.filesDir, PAIRING_KEY_FILE)
        val display = advertisedDisplay

        Log.i(TAG, "starting AirPlay receiver '$name' mac=$mac display=$display")
        val result = NativeAirPlay.nativeStart(
            name = name,
            macAddress = mac,
            keyFilePath = keyFile.absolutePath,
            width = display.width,
            height = display.height,
            fps = display.fps,
            logLevel = NativeAirPlay.LOG_INFO,
            callbacks = this,
        )
        return if (result > 0) {
            publish(ReceiverState.Advertising(name, result, localIpAddress()))
            true
        } else {
            Log.e(TAG, "nativeStart failed with $result")
            publish(ReceiverState.Failed("镜像接收服务启动失败（代码 $result）"))
            false
        }
    }

    fun stop() {
        NativeAirPlay.nativeStop()
        video.stop()
        audio.stop()
        publish(ReceiverState.Stopped)
    }

    // ---------------------------------------------------------------- callbacks

    override fun onLog(level: Int, message: String) {
        // Native side already writes to logcat; nothing extra to do here.
    }

    override fun onClientConnect(name: String, model: String, deviceId: String): Boolean {
        clientName = name.ifBlank { model.ifBlank { "iPhone" } }
        Log.i(TAG, "client connecting: $clientName ($model)")
        return true
    }

    override fun onSessionStart() {
        publishStreaming()
    }

    override fun onSessionEnd() {
        video.stop()
        audio.stop()
        sourceWidth = 0
        sourceHeight = 0
        publishAdvertising()
    }

    override fun onVideoCodec(isH265: Boolean): Int {
        Log.i(TAG, "video codec: ${if (isH265) "H.265" else "H.264"}")
        video.setCodec(isH265)
        return 0
    }

    override fun onVideoData(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        video.submit(data, length, presentationTimeUs)
    }

    override fun onVideoSize(width: Int, height: Int) {
        Log.i(TAG, "source video size: ${width}x$height")
        sourceWidth = width
        sourceHeight = height
        video.setSourceSize(width, height)
        publishStreaming()
    }

    override fun onVideoFlush() {
        video.flush()
    }

    override fun onVideoPause() {
        // No new frames means a still picture; the local presentation queue is bounded.
    }

    override fun onVideoResume() {
        // See onVideoPause.
    }

    override fun onVideoStop() {
        video.stop()
        audio.stop()
        publishAdvertising()
    }

    override fun onAudioFormat(contentType: Int, usingScreen: Boolean, isMedia: Boolean) {
        val codec = AudioPipeline.describeContentType(contentType)
        Log.i(TAG, "audio format $codec usingScreen=$usingScreen isMedia=$isMedia")
        audioRoute = "音频协商：usingScreen=$usingScreen / isMedia=$isMedia"
        audio.setFormat(contentType, restart = true)
        if (!usingScreen && video.framesRendered == 0L) {
            // Audio-only AirPlay: no video will ever arrive, so don't sit on a black screen.
            val playable = AudioPipeline.Format.of(contentType)?.supported == true
            publish(ReceiverState.AudioOnly(clientName, codec, playable))
        }
    }

    override fun onAudioData(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        audio.submit(data, length, presentationTimeUs)
    }

    override fun onAudioFlush() {
        audio.flush()
    }

    override fun onVolume(decibels: Float) {
        audio.setVolumeDecibels(decibels)
    }

    // ------------------------------------------------------------------ helpers

    private fun publishStreaming() {
        publish(ReceiverState.Streaming(clientName, sourceWidth, sourceHeight))
    }

    private fun publishAdvertising() {
        val current = state
        if (current is ReceiverState.Advertising) {
            publish(current)
            return
        }
        val name = DeviceIdentity.serviceName(deviceName(), FALLBACK_NAME)
        publish(ReceiverState.Advertising(name, 0, localIpAddress()))
    }

    private fun publish(next: ReceiverState) {
        state = next
        mainHandler.post { listeners.forEach { it(next) } }
    }

    private fun deviceName(): String = FALLBACK_NAME

    /** Stable per-device seed for the synthetic MAC address. */
    private fun deviceSeed(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return androidId?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER}-${Build.MODEL}"
    }

    private fun detectDisplay(): AdvertisedDisplay {
        val display = runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
            manager?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return AdvertisedDisplay(1920, 1080, 30)

        // mode.physicalWidth/Height is the panel/HDMI mode, which can be 4K even when the
        // Android UI itself is composited at 1080p.
        val mode = display.mode
        return AdvertisedDisplay.from(mode.physicalWidth, mode.physicalHeight, display.refreshRate).let {
            val scale = minOf(1.0, 1920.0 / it.width, 1080.0 / it.height)
            AdvertisedDisplay((it.width * scale).toInt() and -2, (it.height * scale).toInt() and -2, 30)
        }
    }

    private fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "AirPlayReceiver"
        const val FALLBACK_NAME = "小狗便利店投屏"
        const val PAIRING_KEY_FILE = "airplay_pairing.key"
    }
}
