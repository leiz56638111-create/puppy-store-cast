package com.airtv.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/** One Annex-B access unit produced by the test encoder. */
class TestAccessUnit(val bytes: ByteArray, val presentationTimeUs: Long, val isConfig: Boolean)

/**
 * Produces a real H.264 Annex-B elementary stream with the platform encoder, so the decode
 * path can be tested with data shaped like what an iPhone sends.
 */
object H264TestStream {

    fun encode(width: Int, height: Int, frameCount: Int, fps: Int = 30): List<TestAccessUnit> {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val colorFormat = pickColorFormat(encoder)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            .apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val units = ArrayList<TestAccessUnit>()
        val info = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / fps
        var framesQueued = 0

        try {
            while (units.count { !it.isConfig } < frameCount) {
                if (framesQueued < frameCount + 2) {
                    val index = encoder.dequeueInputBuffer(200_000)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)!!
                        input.clear()
                        writeFrame(input, width, height, framesQueued, colorFormat)
                        val endOfStream = framesQueued == frameCount + 1
                        encoder.queueInputBuffer(
                            index, 0, input.position().takeIf { it > 0 } ?: frameSize(width, height, colorFormat),
                            framesQueued * frameDurationUs,
                            if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        framesQueued++
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(info, 200_000)
                if (outIndex >= 0) {
                    val output = encoder.getOutputBuffer(outIndex)!!
                    val bytes = ByteArray(info.size)
                    output.position(info.offset)
                    output.get(bytes)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bytes.isNotEmpty()) {
                        units += TestAccessUnit(bytes, info.presentationTimeUs, isConfig)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
        return units
    }

    /**
     * Reshapes an encoder stream the way the AirPlay mirror protocol actually delivers it:
     * iOS sends the SPS/PPS in a separate control packet, which the receiver library
     * prepends to the following frame, so a standalone parameter-set access unit never
     * appears. The platform encoder does emit one, which is exactly why a test using its
     * raw output can pass while a real iPhone shows a black screen.
     */
    fun asAirPlayShaped(units: List<TestAccessUnit>): List<TestAccessUnit> {
        val out = ArrayList<TestAccessUnit>(units.size)
        var pending: ByteArray? = null
        for (unit in units) {
            if (unit.isConfig) {
                pending = (pending ?: ByteArray(0)) + unit.bytes
                continue
            }
            val bytes = pending?.plus(unit.bytes) ?: unit.bytes
            pending = null
            out += TestAccessUnit(bytes, unit.presentationTimeUs, isConfig = false)
        }
        return out
    }

    private fun pickColorFormat(encoder: MediaCodec): Int {
        val supported = encoder.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .colorFormats
            .toSet()
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        return preferred.firstOrNull { it in supported }
            ?: error("encoder supports no known YUV420 input format: $supported")
    }

    private fun frameSize(width: Int, height: Int, colorFormat: Int): Int =
        width * height * 3 / 2

    /** A moving vertical bar, so successive frames actually differ. */
    private fun writeFrame(buffer: ByteBuffer, width: Int, height: Int, index: Int, colorFormat: Int) {
        val luma = ByteArray(width * height)
        val barX = (index * 11) % width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val near = kotlin.math.abs(x - barX) < 24
                luma[y * width + x] = if (near) 235.toByte() else 16.toByte()
            }
        }
        buffer.put(luma)
        val chromaSize = width * height / 2
        val chroma = ByteArray(chromaSize) { 128.toByte() }
        buffer.put(chroma)
    }
}
