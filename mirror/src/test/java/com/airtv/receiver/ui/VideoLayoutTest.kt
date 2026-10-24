package com.airtv.receiver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mirrored stream keeps the sender's aspect ratio — a portrait iPhone sends a tall
 * frame (e.g. 498x1080). Filling the TV with it stretches the picture, so the video rect
 * has to be fitted inside the screen and centred.
 */
class VideoLayoutTest {

    @Test
    fun `a portrait stream is limited by the screen height`() {
        val rect = VideoLayout.fitInside(498, 1080, 1920, 1080)!!
        assertEquals(498, rect.width)
        assertEquals(1080, rect.height)
    }

    @Test
    fun `a matching landscape stream fills the screen`() {
        val rect = VideoLayout.fitInside(1920, 1080, 1920, 1080)!!
        assertEquals(1920, rect.width)
        assertEquals(1080, rect.height)
    }

    @Test
    fun `a 1080p stream is scaled up to fill a 4k screen`() {
        val rect = VideoLayout.fitInside(1920, 1080, 3840, 2160)!!
        assertEquals(3840, rect.width)
        assertEquals(2160, rect.height)
    }

    @Test
    fun `a four by three stream is pillarboxed on a sixteen by nine screen`() {
        val rect = VideoLayout.fitInside(1440, 1080, 1920, 1080)!!
        assertEquals(1440, rect.width)
        assertEquals(1080, rect.height)
    }

    @Test
    fun `a stream wider than the screen is limited by the screen width`() {
        val rect = VideoLayout.fitInside(2000, 1000, 1000, 1000)!!
        assertEquals(1000, rect.width)
        assertEquals(500, rect.height)
    }

    @Test
    fun `the aspect ratio is preserved when scaling down`() {
        val rect = VideoLayout.fitInside(999, 333, 333, 333)!!
        assertEquals(333, rect.width)
        assertEquals(111, rect.height)
    }

    @Test
    fun `the fitted rect never exceeds the screen`() {
        val rect = VideoLayout.fitInside(1206, 2622, 1920, 1080)!!
        assertEquals(1080, rect.height)
        assertEquals(497, rect.width)
    }

    @Test
    fun `unusable inputs yield null`() {
        assertNull(VideoLayout.fitInside(0, 1080, 1920, 1080))
        assertNull(VideoLayout.fitInside(1920, 0, 1920, 1080))
        assertNull(VideoLayout.fitInside(1920, 1080, 0, 1080))
        assertNull(VideoLayout.fitInside(1920, 1080, 1920, 0))
    }
}
