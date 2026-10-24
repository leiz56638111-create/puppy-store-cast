package com.airtv.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolution/refresh rate we advertise is what iOS uses to pick its mirroring stream
 * geometry, so it has to describe a sane landscape mode capped at UHD.
 */
class AdvertisedDisplayTest {

    @Test
    fun `a 4k 60hz panel is advertised as is`() {
        val display = AdvertisedDisplay.from(3840, 2160, 60f)
        assertEquals(3840, display.width)
        assertEquals(2160, display.height)
        assertEquals(60, display.fps)
    }

    @Test
    fun `a 1080p panel is advertised as is`() {
        val display = AdvertisedDisplay.from(1920, 1080, 59.94f)
        assertEquals(1920, display.width)
        assertEquals(1080, display.height)
        assertEquals(60, display.fps)
    }

    @Test
    fun `resolutions above uhd are capped keeping aspect ratio`() {
        val display = AdvertisedDisplay.from(7680, 4320, 120f)
        assertEquals(3840, display.width)
        assertEquals(2160, display.height)
    }

    @Test
    fun `portrait reports are rotated to landscape`() {
        val display = AdvertisedDisplay.from(1080, 1920, 60f)
        assertEquals(1920, display.width)
        assertEquals(1080, display.height)
    }

    @Test
    fun `unusable metrics fall back to 1080p60`() {
        val display = AdvertisedDisplay.from(0, 0, 0f)
        assertEquals(1920, display.width)
        assertEquals(1080, display.height)
        assertEquals(60, display.fps)
    }

    @Test
    fun `refresh rate is clamped to a sane range`() {
        assertEquals(60, AdvertisedDisplay.from(1920, 1080, 240f).fps)
        assertEquals(30, AdvertisedDisplay.from(1920, 1080, 30f).fps)
        assertEquals(24, AdvertisedDisplay.from(1920, 1080, 10f).fps)
    }

    @Test
    fun `dimensions are rounded to even numbers`() {
        val display = AdvertisedDisplay.from(1367, 769, 60f)
        assertEquals(1366, display.width)
        assertEquals(768, display.height)
    }
}
