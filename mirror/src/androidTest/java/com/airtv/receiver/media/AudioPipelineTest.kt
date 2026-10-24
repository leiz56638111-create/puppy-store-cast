package com.airtv.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {

    private val pipeline = AudioPipeline()

    @After
    fun tearDown() {
        pipeline.stop()
    }

    @Test
    fun aacLcStreamIsDecodedAndPlayed() {
        val frames = encodeAacLc(frameCount = 40)
        assertTrue("test encoder produced no audio", frames.size >= 10)

        pipeline.setFormat(AudioPipeline.Format.AAC_LC.contentType)
        assertTrue("audio pipeline did not start", pipeline.isRunning)

        var pts = 0L
        for (frame in frames) {
            pipeline.submit(ByteBuffer.wrap(frame), frame.size, pts)
            pts += 1024L * 1_000_000L / 44_100L
        }

        assertTrue(
            "no audio buffers reached the AudioTrack (${pipeline.framesPlayed})",
            waitFor(5_000) { pipeline.framesPlayed > 0 },
        )
    }

    @Test
    fun repeatedFlushesDoNotLoseAudioOutput() {
        val frames = encodeAacLc(frameCount = 35)
        pipeline.setFormat(4)
        var pts = 0L
        repeat(8) {
            val before = pipeline.framesPlayed
            frames.forEach { frame ->
                pipeline.submit(ByteBuffer.wrap(frame), frame.size, pts)
                pts += 1024L * 1_000_000L / 44100L
            }
            assertTrue("audio did not recover in cycle $it", waitFor(5000) { pipeline.framesPlayed > before })
            pipeline.flush()
        }
    }

    @Test
    fun audioRecoveryPreservesMuteAndAcceptsANewSetup() {
        assertTrue("no negotiated audio must not claim recovery", !pipeline.recover())
        pipeline.setFormat(2)
        assertTrue("unsupported ALAC must not claim recovery", !pipeline.recover())
        val frames = encodeAacLc(frameCount = 35)
        pipeline.setFormat(4)
        pipeline.setVolumeDecibels(-144f)
        assertTrue(pipeline.recover())
        assertTrue("recovery changed sender mute", pipeline.diagnostics().contains("音量：0%"))
        pipeline.setFormat(4, restart = true)
        pipeline.setVolumeDecibels(0f)
        frames.forEachIndexed { i, frame ->
            pipeline.submit(ByteBuffer.wrap(frame), frame.size, i * 1024L * 1_000_000 / 44100)
        }
        assertTrue("a new SETUP must still produce PCM", waitFor(5000) { pipeline.framesPlayed > 0 })
        assertTrue(pipeline.diagnostics(), pipeline.diagnostics().contains("44100Hz / 2声道"))
    }

    /**
     * Mirroring always uses AAC-ELD, for which Android has no encoder, so we cannot do a
     * round trip. Configuring a real decoder with our AudioSpecificConfig still proves the
     * profile and csd-0 we build are accepted by the platform.
     */
    @Test
    fun aacEldConfigIsAcceptedByThePlatformDecoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 39) // AACObjectELD
            setByteBuffer("csd-0", ByteBuffer.wrap(AudioSpecificConfig.aacEld(44100, 2)))
        }
        val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        assertTrue("no decoder claims support for AAC-ELD 44100/2", decoderName != null)

        val codec = MediaCodec.createByCodecName(decoderName!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    @Test
    fun alacIsRejectedWithoutStartingAnything() {
        pipeline.setFormat(AudioPipeline.Format.ALAC.contentType)
        assertTrue("ALAC must not start a decoder", !pipeline.isRunning)
    }

    @Test
    fun unknownFormatIsIgnored() {
        pipeline.setFormat(99)
        assertTrue(!pipeline.isRunning)
    }

    private fun encodeAacLc(frameCount: Int): List<ByteArray> {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AACObjectLC
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val out = ArrayList<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var pts = 0L
        val samplesPerFrame = 1024
        try {
            while (out.size < frameCount && queued < frameCount + 10) {
                val index = encoder.dequeueInputBuffer(200_000)
                if (index >= 0) {
                    val input = encoder.getInputBuffer(index)!!
                    input.clear()
                    // 440 Hz sine, 16-bit stereo
                    for (i in 0 until samplesPerFrame) {
                        val t = (queued * samplesPerFrame + i).toDouble() / 44100.0
                        val value = (Math.sin(2 * Math.PI * 440 * t) * 12000).toInt().toShort()
                        input.putShort(value)
                        input.putShort(value)
                    }
                    encoder.queueInputBuffer(index, 0, samplesPerFrame * 4, pts, 0)
                    pts += samplesPerFrame * 1_000_000L / 44_100L
                    queued++
                }
                val outIndex = encoder.dequeueOutputBuffer(info, 200_000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        encoder.getOutputBuffer(outIndex)!!.apply {
                            position(info.offset)
                            get(bytes)
                        }
                        out += bytes
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
        return out
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
