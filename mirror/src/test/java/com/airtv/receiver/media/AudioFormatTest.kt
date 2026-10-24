package com.airtv.receiver.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sender opens an audio-only session the TV has to be able to say which codec it is
 * and whether we can play it, instead of showing a blank screen.
 */
class AudioFormatTest {

    @Test
    fun `content types map to the airplay codecs`() {
        assertEquals(AudioPipeline.Format.ALAC, AudioPipeline.Format.of(2))
        assertEquals(AudioPipeline.Format.AAC_LC, AudioPipeline.Format.of(4))
        assertEquals(AudioPipeline.Format.AAC_ELD, AudioPipeline.Format.of(8))
    }

    @Test
    fun `an unknown content type has no format`() {
        assertNull(AudioPipeline.Format.of(99))
    }

    @Test
    fun `aac formats are playable and alac is not`() {
        assertTrue(AudioPipeline.Format.AAC_ELD.supported)
        assertTrue(AudioPipeline.Format.AAC_LC.supported)
        assertFalse("Android has no guaranteed ALAC decoder", AudioPipeline.Format.ALAC.supported)
    }

    @Test
    fun `formats have names fit for the screen`() {
        assertEquals("AAC-ELD", AudioPipeline.Format.AAC_ELD.displayName)
        assertEquals("AAC-LC", AudioPipeline.Format.AAC_LC.displayName)
        assertEquals("ALAC (Apple Lossless)", AudioPipeline.Format.ALAC.displayName)
    }

    @Test
    fun `an unknown content type is described by its number`() {
        assertEquals("unknown (ct=99)", AudioPipeline.describeContentType(99))
        assertEquals("AAC-ELD", AudioPipeline.describeContentType(8))
    }
}
