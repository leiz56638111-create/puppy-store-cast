package com.airtv.receiver.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AirPlay mirroring protocol always negotiates 44100 Hz stereo audio. The expected
 * AudioSpecificConfig byte strings below are the ones known-good receivers feed their
 * decoders (UxPlay uses codec_data=f8e85000 for AAC-ELD and 1210 for AAC-LC).
 */
class AudioSpecificConfigTest {

    @Test
    fun `aac eld config for 44100 stereo matches known good bytes`() {
        val config = AudioSpecificConfig.aacEld(sampleRate = 44100, channels = 2)
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00), config)
    }

    @Test
    fun `aac lc config for 44100 stereo matches known good bytes`() {
        val config = AudioSpecificConfig.aacLc(sampleRate = 44100, channels = 2)
        assertArrayEquals(byteArrayOf(0x12, 0x10), config)
    }

    @Test
    fun `sampling frequency index follows the mpeg4 table`() {
        assertEquals(3, AudioSpecificConfig.samplingFrequencyIndex(48000))
        assertEquals(4, AudioSpecificConfig.samplingFrequencyIndex(44100))
        assertEquals(11, AudioSpecificConfig.samplingFrequencyIndex(8000))
    }

    @Test
    fun `unknown sample rate is rejected`() {
        try {
            AudioSpecificConfig.samplingFrequencyIndex(12345)
            throw AssertionError("expected an exception for an unsupported sample rate")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `aac eld config for 48000 mono differs from stereo`() {
        val mono = AudioSpecificConfig.aacEld(sampleRate = 48000, channels = 1)
        // escape(11111) ext(000111) freqIdx 3(0011) chan 1(0001) frameLenFlag 1 then zeros
        assertArrayEquals(byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30, 0x00), mono)
    }
}
