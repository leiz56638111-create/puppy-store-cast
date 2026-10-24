package com.airtv.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Decodes the AirPlay audio stream (AAC-ELD for mirroring, AAC-LC for some senders) and
 * plays it through an [AudioTrack].
 */
class AudioPipeline {

    /** AirPlay "ct" content types. */
    enum class Format(val contentType: Int, val displayName: String, val supported: Boolean) {
        /** Audio-only AirPlay; Android has no guaranteed ALAC decoder. */
        ALAC(2, "ALAC (Apple Lossless)", supported = false),
        AAC_LC(4, "AAC-LC", supported = true),

        /** What screen mirroring always uses. */
        AAC_ELD(8, "AAC-ELD", supported = true),
        ;

        companion object {
            fun of(contentType: Int): Format? = entries.firstOrNull { it.contentType == contentType }
        }
    }

    companion object {
        fun describeContentType(contentType: Int): String =
            Format.of(contentType)?.displayName ?: "unknown (ct=$contentType)"

        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val AAC_PROFILE_LC = 2
        private const val AAC_PROFILE_ELD = 39
        private const val MUTED_DB = -30f
        private const val INPUT_TIMEOUT_US = 20_000L
    }

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var format: Format? = null
    private var drainThread: Thread? = null
    private var generation = 0L
    private var packetsReceived = 0L
    private var resets = 0L
    private var lastError = "无"
    private var retryAfterMs = 0L
    private var outputDescription = "等待解码输出"

    fun diagnostics(): String = synchronized(lock) {
        "音频：${format?.displayName ?: "尚未协商"} / ${if (isRunning) "运行中" else "未运行"}\n" +
            "收到音频包：$packetsReceived / 已输出：$framesPlayed\n" +
            "输出：$outputDescription / 音量：${(gain * 100).toInt()}%\n" +
            "音频重建：$resets / 最近错误：$lastError"
    }

    /** Rebuild just audio; preserve the current AirPlay volume and video connection. */
    fun recover(): Boolean = synchronized(lock) {
        val target = format?.takeIf { it.supported } ?: return@synchronized false
        stopLocked()
        resets++
        startLocked(target)
        isRunning
    }

    @Volatile
    private var draining = false

    @Volatile
    private var gain = 1.0f

    @Volatile
    var framesPlayed = 0L
        private set

    val isRunning: Boolean get() = synchronized(lock) { codec != null && draining && drainThread?.isAlive == true }

    /** Called when the sender announces the audio format for a new stream. */
    fun setFormat(contentType: Int, restart: Boolean = false) {
        synchronized(lock) {
            val requested = Format.of(contentType)
            if (requested == null) {
                Log.w(TAG, "unknown audio content type $contentType, ignoring audio")
                stopLocked()
                format = null
                lastError = "不支持的音频格式 ct=$contentType"
                return
            }
            if (requested == Format.ALAC) {
                // Only reached for audio-only AirPlay from senders that pick ALAC;
                // Android has no guaranteed ALAC decoder, so we stay silent instead of
                // feeding a decoder that will error out on every frame.
                Log.w(TAG, "ALAC audio is not supported on Android; audio disabled")
                stopLocked()
                format = requested
                lastError = "当前不支持 ALAC 音频"
                return
            }
            if (!restart && format == requested && isRunning) return
            stopLocked()
            format = requested
            startLocked(requested)
        }
    }

    fun submit(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (length <= 0 || length > data.limit()) return
        val bytes = ByteArray(length)
        data.duplicate().apply { position(0); get(bytes) }
        val activeCodec = synchronized(lock) {
            packetsReceived++
            if (codec == null && format?.supported == true &&
                android.os.SystemClock.elapsedRealtime() >= retryAfterMs) {
                retryAfterMs = android.os.SystemClock.elapsedRealtime() + 1000
                startLocked(format!!)
            }
            codec
        } ?: return
        val deadline = System.nanoTime() + INPUT_TIMEOUT_US * 1000
        do {
            val queued = synchronized(lock) {
                if (codec !== activeCodec) return
                try {
                    val index = activeCodec.dequeueInputBuffer(0)
                    if (index < 0) false else {
                        val input = activeCodec.getInputBuffer(index) ?: error("missing audio input")
                        input.clear()
                        input.put(bytes)
                        activeCodec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
                        true
                    }
                } catch (e: Exception) {
                    failLocked("音频输入失败", e)
                    return
                }
            }
            if (queued) return
            Thread.sleep(1)
        } while (System.nanoTime() < deadline)
    }

    fun flush() {
        // Recreate the synchronous decoder and PCM sink together. A flush invalidates
        // output indices; the generation prevents old workers writing into a new sink.
        recover()
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            format = null
        }
    }

    /** AirPlay volume: 0 dB is loudest, -30 dB quietest, -144 dB muted. */
    fun setVolumeDecibels(decibels: Float) {
        gain = when {
            decibels <= MUTED_DB -> 0f
            decibels >= 0f -> 1f
            else -> 10.0.pow(decibels / 20.0).toFloat().coerceIn(0f, 1f)
        }
        synchronized(lock) {
            try {
                track?.setVolume(gain)
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun startLocked(target: Format) {
        try {
            val csd = when (target) {
                Format.AAC_ELD -> AudioSpecificConfig.aacEld(SAMPLE_RATE, CHANNELS)
                Format.AAC_LC -> AudioSpecificConfig.aacLc(SAMPLE_RATE, CHANNELS)
                Format.ALAC -> return
            }
            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    if (target == Format.AAC_ELD) AAC_PROFILE_ELD else AAC_PROFILE_LC,
                )
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }

            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = newCodec // cleanup also owns partially configured codecs
            newCodec.configure(mediaFormat, null, null, 0)
            newCodec.start()
            outputDescription = "等待解码输出"
            startDrainLocked(newCodec)
            Log.i(TAG, "audio started: $target ${SAMPLE_RATE}Hz x$CHANNELS")
        } catch (e: Exception) {
            failLocked("音频启动失败", e)
        }
    }

    private fun buildAudioTrack(sampleRate: Int, channels: Int, encoding: Int): AudioTrack {
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, encoding,
        ).coerceAtLeast(8 * 1024)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    private fun startDrainLocked(activeCodec: MediaCodec) {
        draining = true
        val token = generation
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var pending = ByteArray(0)
            var offset = 0
            while (true) {
                var progress = false
                synchronized(lock) {
                    if (!draining || token != generation || codec !== activeCodec) return@Thread
                    try {
                        if (offset < pending.size) {
                            val sink = track ?: error("missing PCM output")
                            val written = sink.write(pending, offset, pending.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0) { "AudioTrack.write=$written" }
                            offset += written
                            progress = written > 0
                            if (offset == pending.size) framesPlayed++
                        } else {
                            val index = activeCodec.dequeueOutputBuffer(info, 0)
                            when {
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val actual = activeCodec.outputFormat
                                    val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    check(channels in 1..2) { "unsupported channel count $channels" }
                                    val encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                    track?.release()
                                    track = buildAudioTrack(rate, channels, encoding)
                                    track!!.setVolume(gain)
                                    track!!.play()
                                    outputDescription = "${rate}Hz / ${channels}声道 / PCM $encoding"
                                    progress = true
                                }
                                index >= 0 -> {
                                    val output = activeCodec.getOutputBuffer(index)
                                    pending = ByteArray(info.size)
                                    offset = 0
                                    if (info.size > 0) {
                                        check(output != null) { "missing audio output" }
                                        output.position(info.offset)
                                        output.get(pending)
                                    }
                                    activeCodec.releaseOutputBuffer(index, false)
                                    progress = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failLocked("音频输出失败", e)
                        return@Thread
                    }
                }
                if (!progress) Thread.sleep(4)
            }
        }, "airtv-audio-drain").apply { start() }
    }

    private fun failLocked(message: String, error: Exception) {
        lastError = "$message：${error.javaClass.simpleName} ${error.message}"
        Log.w(TAG, message, error)
        stopLocked()
        retryAfterMs = android.os.SystemClock.elapsedRealtime() + 1000
    }

    private fun stopLocked() {
        draining = false
        generation++
        // Never join while holding the same lock the worker needs. A retired worker
        // checks its generation before touching resources and exits on its own.
        drainThread = null
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

}
