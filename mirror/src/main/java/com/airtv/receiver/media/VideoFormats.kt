package com.airtv.receiver.media

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/** Builds the decoder input formats used by [VideoPipeline]. */
object VideoFormats {

    const val MAX_FPS = 60

    /**
     * The picture size a decoder is actually producing: the crop rectangle if the stream
     * signals one, otherwise the coded size. Null if the format carries no geometry yet.
     */
    fun displaySize(format: MediaFormat): Pair<Int, Int>? {
        val cropped = runCatching {
            val left = format.getInteger("crop-left")
            val right = format.getInteger("crop-right")
            val top = format.getInteger("crop-top")
            val bottom = format.getInteger("crop-bottom")
            (right - left + 1) to (bottom - top + 1)
        }.getOrNull()
        if (cropped != null && cropped.first > 0 && cropped.second > 0) return cropped

        return runCatching {
            format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
        }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }
    }

    /**
     * @param tuned adds the optional low-latency / realtime hints. They measurably reduce
     * mirroring lag, but they are advisory and some decoders refuse to configure with them,
     * so the pipeline retries with `tuned = false`.
     */
    fun create(
        codecConfig: ByteArray,
        width: Int,
        height: Int,
        isH265: Boolean,
        tuned: Boolean,
    ): MediaFormat {
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
            if (tuned) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_OPERATING_RATE, MAX_FPS)
            }
        }
    }
}
