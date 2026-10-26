// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct
import android.media.MediaCodec
import android.media.MediaFormat
object AacTestStream {
    fun encodeAacLc(frameCount: Int): List<ByteArray> {
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

}
