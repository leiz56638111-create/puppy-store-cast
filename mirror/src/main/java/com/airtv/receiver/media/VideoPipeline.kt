package com.airtv.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the Annex-B H.264/H.265 access units produced by the AirPlay mirror stream
 * straight onto a [Surface].
 *
 * Frames follow their presentation timestamps, including when network input arrives
 * in a burst. Discontinuities re-anchor the local presentation clock.
 */
class VideoPipeline {

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isH265 = false
    private var codecConfig: ByteArray? = null
    private var needKeyFrame = true
    private var sourceWidth = DEFAULT_WIDTH
    private var sourceHeight = DEFAULT_HEIGHT
    private var scratch = ByteArray(256 * 1024)

    private var drainThread: Thread? = null
    private var generation = 0L

    @Volatile
    private var draining = false

    @Volatile
    var framesRendered = 0L
        private set

    @Volatile
    var framesDropped = 0L
        private set

    /**
     * Reports the geometry the decoder is actually producing (width, height), which is what
     * the picture must be letterboxed to. Fires from the decoder thread whenever the output
     * format changes — including mid-session, e.g. when the sender is rotated.
     */
    @Volatile
    var onDecodedSize: ((Int, Int) -> Unit)? = null

    /** Attaches (or detaches, with null) the output surface. */
    fun setSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (surface === newSurface) return
            releaseCodecLocked()
            surface = newSurface
            startCodecIfPossibleLocked()
        }
    }

    fun setCodec(h265: Boolean) {
        synchronized(lock) {
            if (isH265 == h265 && codec != null) return
            isH265 = h265
            codecConfig = null
            releaseCodecLocked()
        }
    }

    fun setSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == sourceWidth && height == sourceHeight) return
            sourceWidth = width
            sourceHeight = height
            // The decoder adapts from the in-band parameter sets; only a fresh session
            // needs the new defaults, so nothing to restart here.
        }
    }

    /** Submits one access unit. The buffer contents are copied before returning. */
    fun submit(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (length <= 0 || length > data.limit()) return
        val bytes = ByteArray(length)
        data.duplicate().apply { position(0); get(bytes) }
        val activeCodec = synchronized(lock) {
            if (scratch.size < length) scratch = ByteArray(length * 2)
            bytes.copyInto(scratch)

            // The parameter sets usually arrive prepended to a keyframe rather than as an
            // access unit of their own, so look for them in every buffer.
            val parameterSets = NalUnits.extractParameterSets(scratch, length, isH265)
            if (parameterSets != null && !parameterSets.contentEquals(codecConfig)) {
                Log.i(
                    TAG,
                    "parameter sets (${NalUnits.describe(parameterSets, parameterSets.size, isH265)})" +
                        " in access unit (${NalUnits.describe(scratch, length, isH265)})",
                )
                codecConfig = parameterSets
                releaseCodecLocked()
                startCodecIfPossibleLocked()
            }
            if (NalUnits.isCodecConfig(scratch, length, isH265)) {
                // Parameter sets only: they went into the decoder configuration above.
                return
            }

            val activeCodec = codec ?: run {
                dropFrame("no decoder yet (config=${codecConfig != null} surface=${surface != null})")
                return
            }
            if (needKeyFrame) {
                if (!NalUnits.isKeyFrame(scratch, length, isH265)) {
                    dropFrame("waiting for a keyframe")
                    return
                }
                needKeyFrame = false
            }
            activeCodec
        }
        val deadline = System.nanoTime() + INPUT_TIMEOUT_US * 1000
        do {
            val queued = synchronized(lock) {
                if (codec !== activeCodec) return
                try {
                    val index = activeCodec.dequeueInputBuffer(0)
                    if (index < 0) false else {
                        val input = activeCodec.getInputBuffer(index) ?: error("missing video input")
                        input.clear()
                        input.put(bytes)
                        activeCodec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "decoder rejected input, restarting", e)
                    releaseCodecLocked()
                    startCodecIfPossibleLocked()
                    return
                }
            }
            if (queued) return
            Thread.sleep(1)
        } while (System.nanoTime() < deadline)
        dropFrame("no free decoder input buffer")
    }

    fun flush() {
        synchronized(lock) {
            releaseCodecLocked()
            startCodecIfPossibleLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            releaseCodecLocked()
            codecConfig = null
            framesRendered = 0
            framesDropped = 0
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { codec != null }

    /**
     * Dropping frames silently once hid a total failure to render behind a black screen, so
     * every dropped frame has a logged reason (rate limited to stay usable).
     */
    private fun dropFrame(reason: String) {
        framesDropped++
        if (framesDropped == 1L || framesDropped % DROP_LOG_INTERVAL == 0L) {
            Log.w(TAG, "dropped $framesDropped video frames: $reason")
        }
    }

    private fun startCodecIfPossibleLocked() {
        if (codec != null) return
        val outputSurface = surface ?: return
        val config = codecConfig ?: return
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // The low-latency hints are advisory; retry without them rather than losing video.
        for (tuned in listOf(true, false)) {
            var candidate: MediaCodec? = null
            val created = try {
                val format =
                    VideoFormats.create(config, sourceWidth, sourceHeight, isH265, tuned)
                MediaCodec.createDecoderByType(mime).also { candidate = it }.apply {
                    configure(format, outputSurface, null, 0)
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "decoder start failed for $mime (tuned=$tuned)", e)
                candidate?.let { runCatching { it.release() } }
                continue
            }
            codec = created
            needKeyFrame = true
            startDrainLocked(created)
            Log.i(TAG, "decoder started: $mime ${sourceWidth}x$sourceHeight tuned=$tuned")
            return
        }
        Log.e(TAG, "no usable decoder for $mime")
        codec = null
    }

    private fun startDrainLocked(activeCodec: MediaCodec) {
        draining = true
        val token = generation
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var pendingIndex = -1
            var targetNs = 0L
            var originPtsUs = Long.MIN_VALUE
            var originNs = 0L
            var previousPtsUs = Long.MIN_VALUE
            while (true) {
                var progress = false
                synchronized(lock) {
                    if (!draining || token != generation || codec !== activeCodec) return@Thread
                    try {
                        if (pendingIndex >= 0) {
                            if (System.nanoTime() >= targetNs) {
                                activeCodec.releaseOutputBuffer(pendingIndex, true)
                                framesRendered++
                                pendingIndex = -1
                                progress = true
                            }
                        } else {
                            val index = activeCodec.dequeueOutputBuffer(info, 0)
                            when {
                                index >= 0 -> {
                                    val now = System.nanoTime()
                                    val pts = info.presentationTimeUs
                                    val discontinuity = previousPtsUs != Long.MIN_VALUE &&
                                        (pts < previousPtsUs - 500_000 || pts > previousPtsUs + 2_000_000)
                                    var due = if (originPtsUs == Long.MIN_VALUE) now else
                                        originNs + (pts - originPtsUs) * 1000
                                    if (originPtsUs == Long.MIN_VALUE || discontinuity ||
                                        due < now - 500_000_000L || due > now + 2_000_000_000L) {
                                        originPtsUs = pts
                                        originNs = now
                                        due = now
                                    }
                                    previousPtsUs = pts
                                    targetNs = due
                                    pendingIndex = index
                                    progress = true
                                }
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val format = activeCodec.outputFormat
                                    Log.i(TAG, "output format: $format")
                                    VideoFormats.displaySize(format)?.let { (width, height) ->
                                        onDecodedSize?.invoke(width, height)
                                    }
                                    progress = true
                                }
                            }
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "video output stopped", e)
                        releaseCodecLocked()
                        return@Thread
                    }
                }
                if (!progress) Thread.sleep(4)
            }
        }, "airtv-video-drain").apply { start() }
    }

    private fun releaseCodecLocked() {
        draining = false
        generation++
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
        needKeyFrame = true
    }

    private companion object {
        const val TAG = "VideoPipeline"
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DROP_LOG_INTERVAL = 60L
        const val INPUT_TIMEOUT_US = 100_000L
    }
}
