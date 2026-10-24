package com.airtv.receiver.media

/** MPEG-4 AudioSpecificConfig builder (ISO/IEC 14496-3 §1.6.2.1). */
object AudioSpecificConfig {

    private const val AOT_AAC_LC = 2
    private const val AOT_AAC_ELD = 39
    private const val AOT_ESCAPE = 31

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    fun samplingFrequencyIndex(sampleRate: Int): Int {
        val index = SAMPLE_RATES.indexOf(sampleRate)
        require(index >= 0) { "unsupported sample rate: $sampleRate" }
        return index
    }

    fun aacLc(sampleRate: Int, channels: Int): ByteArray {
        val writer = BitWriter(2)
        writer.write(AOT_AAC_LC, 5)
        writer.write(samplingFrequencyIndex(sampleRate), 4)
        writer.write(channels, 4)
        // GASpecificConfig: frameLengthFlag, dependsOnCoreCoder, extensionFlag
        writer.write(0, 3)
        return writer.toByteArray()
    }

    /**
     * AAC-ELD, as sent by iOS during AirPlay mirroring: 480 samples per frame
     * (frameLengthFlag = 1), no error-resilience tools, no SBR.
     */
    fun aacEld(sampleRate: Int, channels: Int): ByteArray {
        val writer = BitWriter(4)
        writer.write(AOT_ESCAPE, 5)
        writer.write(AOT_AAC_ELD - 32, 6)
        writer.write(samplingFrequencyIndex(sampleRate), 4)
        writer.write(channels, 4)
        // ELDSpecificConfig
        writer.write(1, 1) // frameLengthFlag: 480 samples
        writer.write(0, 1) // aacSectionDataResilienceFlag
        writer.write(0, 1) // aacScalefactorDataResilienceFlag
        writer.write(0, 1) // aacSpectralDataResilienceFlag
        writer.write(0, 1) // ldSbrPresentFlag
        writer.write(0, 4) // ELDEXT_TERM
        return writer.toByteArray()
    }

    private class BitWriter(sizeBytes: Int) {
        private val bytes = ByteArray(sizeBytes)
        private var bitPosition = 0

        fun write(value: Int, bitCount: Int) {
            for (i in bitCount - 1 downTo 0) {
                val bit = (value ushr i) and 1
                if (bit != 0) {
                    val index = bitPosition ushr 3
                    val shift = 7 - (bitPosition and 7)
                    bytes[index] = (bytes[index].toInt() or (1 shl shift)).toByte()
                }
                bitPosition++
            }
        }

        fun toByteArray(): ByteArray = bytes
    }
}
