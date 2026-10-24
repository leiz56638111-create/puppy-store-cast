package com.airtv.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tuning keys we ask for (low latency, realtime priority, operating rate) are optional
 * and some decoders reject them outright, so the pipeline must be able to fall back to a
 * plain format. Both variants have to be usable.
 */
@RunWith(AndroidJUnit4::class)
class VideoFormatsTest {

    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)

    @Test
    fun tunedFormatCarriesTheLowLatencyHints() {
        val format = VideoFormats.create(config, 1920, 1080, isH265 = false, tuned = true)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(1, format.getInteger(MediaFormat.KEY_LOW_LATENCY))
        }
        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertTrue(format.containsKey(MediaFormat.KEY_OPERATING_RATE))
    }

    @Test
    fun untunedFormatOmitsTheOptionalKeys() {
        val format = VideoFormats.create(config, 1920, 1080, isH265 = false, tuned = false)
        assertFalse(format.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(format.containsKey(MediaFormat.KEY_OPERATING_RATE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertFalse(format.containsKey(MediaFormat.KEY_LOW_LATENCY))
        }
    }

    @Test
    fun bothVariantsCarryTheParameterSetsAndGeometry() {
        for (tuned in listOf(true, false)) {
            val format = VideoFormats.create(config, 1280, 720, isH265 = false, tuned = tuned)
            assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
            val csd = format.getByteBuffer("csd-0")!!
            val bytes = ByteArray(csd.remaining()).also { csd.get(it) }
            assertTrue("csd-0 missing for tuned=$tuned", bytes.contentEquals(config))
        }
    }

    @Test
    fun h265FormatUsesTheHevcMimeType() {
        val format = VideoFormats.create(config, 1920, 1080, isH265 = true, tuned = true)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, format.getString(MediaFormat.KEY_MIME))
    }

    /**
     * Encoders often round the coded size up to a macroblock multiple and signal the real
     * picture size with a crop rectangle; using the coded size would letterbox the picture
     * slightly wrong, so the crop must win.
     */
    @Test
    fun displaySizeUsesTheCropRectangleWhenPresent() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1088)
        format.setInteger("crop-left", 0)
        format.setInteger("crop-right", 1919)
        format.setInteger("crop-top", 0)
        format.setInteger("crop-bottom", 1079)
        assertEquals(1920 to 1080, VideoFormats.displaySize(format))
    }

    @Test
    fun displaySizeFallsBackToTheCodedSize() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 498, 1080)
        assertEquals(498 to 1080, VideoFormats.displaySize(format))
    }

    @Test
    fun displaySizeIsNullWithoutGeometry() {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC)
        assertEquals(null, VideoFormats.displaySize(format))
    }

    @Test
    fun aRealDecoderReportsTheStreamGeometry() {
        val stream = H264TestStream.encode(640, 360, frameCount = 4)
        val realConfig = stream.first { it.isConfig }.bytes
        val format = VideoFormats.create(realConfig, 640, 360, isH265 = false, tuned = false)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            // csd-0 alone is enough for the decoder to publish the output format
            val size = VideoFormats.displaySize(codec.outputFormat)
            assertEquals(640 to 360, size)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    @Test
    fun aRealDecoderStartsWithTheUntunedFormat() {
        val stream = H264TestStream.encode(640, 360, frameCount = 2)
        val realConfig = stream.first { it.isConfig }.bytes
        val format = VideoFormats.create(realConfig, 640, 360, isH265 = false, tuned = false)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
