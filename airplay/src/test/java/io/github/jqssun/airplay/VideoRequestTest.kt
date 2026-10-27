// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay
import org.junit.Assert.*
import org.junit.Test
class VideoRequestTest {
    @Test fun validatesNetworkLocations() {
        assertTrue(VideoRequest.accepted("http://127.0.0.1:7000/master.m3u8"))
        assertFalse(VideoRequest.accepted("file:///sdcard/private"))
        assertFalse(VideoRequest.accepted("https://user:password@example.org/video"))
        assertFalse(VideoRequest.accepted("https://example.org/\r\nX: value"))
        assertFalse(VideoRequest.accepted("https://example.org/" + "x".repeat(32768)))
    }
    @Test fun boundsStartPosition() { assertEquals(0f, VideoRequest.startSeconds(Float.NaN)); assertEquals(0f, VideoRequest.startSeconds(-1f)); assertEquals(0f, VideoRequest.startSeconds(700000f)); assertEquals(12f, VideoRequest.startSeconds(12f)) }
}
