package com.airtv.receiver.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native layer hands us Annex-B (00 00 00 01 start codes) access units. We need to
 * recognise parameter-set-only access units so they can be queued with
 * MediaCodec.BUFFER_FLAG_CODEC_CONFIG, and keyframes so a decoder can be (re)started.
 */
class NalUnitsTest {

    private fun annexB(vararg nalHeaderBytes: Int): ByteArray {
        val out = ArrayList<Byte>()
        for (b in nalHeaderBytes) {
            out.addAll(listOf(0, 0, 0, 1))
            out.add(b.toByte())
            out.addAll(listOf<Byte>(0x11, 0x22)) // arbitrary payload
        }
        return out.toByteArray()
    }

    @Test
    fun `h264 sps plus pps access unit is codec config`() {
        val au = annexB(0x67, 0x68) // SPS (type 7), PPS (type 8)
        assertTrue(NalUnits.isCodecConfig(au, au.size, isH265 = false))
    }

    @Test
    fun `h264 idr access unit is not codec config`() {
        val au = annexB(0x65) // IDR (type 5)
        assertFalse(NalUnits.isCodecConfig(au, au.size, isH265 = false))
    }

    @Test
    fun `h264 idr access unit is a keyframe`() {
        assertTrue(NalUnits.isKeyFrame(annexB(0x65), 7, isH265 = false))
        assertFalse(NalUnits.isKeyFrame(annexB(0x41), 7, isH265 = false))
    }

    @Test
    fun `h264 sps prefixed idr is a keyframe but not codec config`() {
        val au = annexB(0x67, 0x68, 0x65)
        assertTrue(NalUnits.isKeyFrame(au, au.size, isH265 = false))
        assertFalse(NalUnits.isCodecConfig(au, au.size, isH265 = false))
    }

    @Test
    fun `h265 vps sps pps access unit is codec config`() {
        // H.265 nal type is bits 1..6 of the first header byte: VPS=32, SPS=33, PPS=34
        val au = annexB(32 shl 1, 33 shl 1, 34 shl 1)
        assertTrue(NalUnits.isCodecConfig(au, au.size, isH265 = true))
    }

    @Test
    fun `h265 idr is a keyframe`() {
        assertTrue(NalUnits.isKeyFrame(annexB(19 shl 1), 7, isH265 = true)) // IDR_W_RADL
        assertFalse(NalUnits.isKeyFrame(annexB(1 shl 1), 7, isH265 = true)) // TRAIL_R
    }

    @Test
    fun `only the first length bytes are inspected`() {
        val au = annexB(0x67, 0x65)
        // Limit the length so the trailing IDR is invisible: still codec config.
        assertTrue(NalUnits.isCodecConfig(au, 7, isH265 = false))
        assertFalse(NalUnits.isCodecConfig(au, au.size, isH265 = false))
    }

    @Test
    fun `access unit without start code is neither`() {
        val junk = byteArrayOf(0x11, 0x22, 0x33)
        assertFalse(NalUnits.isCodecConfig(junk, junk.size, isH265 = false))
        assertFalse(NalUnits.isKeyFrame(junk, junk.size, isH265 = false))
    }

    @Test
    fun `three byte start codes are also recognised`() {
        val au = byteArrayOf(0, 0, 1, 0x67, 0x11, 0, 0, 1, 0x68, 0x22)
        assertTrue(NalUnits.isCodecConfig(au, au.size, isH265 = false))
    }

    @Test
    fun `describe lists nal types for logging`() {
        assertEquals("7,8", NalUnits.describe(annexB(0x67, 0x68), 14, isH265 = false))
    }

    // iOS never sends a parameter-set-only access unit: the mirror protocol carries the
    // SPS/PPS in a separate control packet, which the library prepends to the next frame.
    // So the parameter sets have to be recoverable from an access unit that also has slices.

    @Test
    fun `parameter sets are extracted from an sps pps idr access unit`() {
        val au = annexB(0x67, 0x68, 0x65)
        val extracted = NalUnits.extractParameterSets(au, au.size, isH265 = false)
        assertArrayEquals(annexB(0x67, 0x68), extracted)
    }

    @Test
    fun `parameter sets are extracted from a config only access unit`() {
        val au = annexB(0x67, 0x68)
        assertArrayEquals(au, NalUnits.extractParameterSets(au, au.size, isH265 = false))
    }

    @Test
    fun `an access unit without parameter sets yields null`() {
        val au = annexB(0x65)
        assertNull(NalUnits.extractParameterSets(au, au.size, isH265 = false))
    }

    @Test
    fun `h265 vps sps pps are extracted from a keyframe access unit`() {
        val au = annexB(32 shl 1, 33 shl 1, 34 shl 1, 19 shl 1)
        val extracted = NalUnits.extractParameterSets(au, au.size, isH265 = true)
        assertArrayEquals(annexB(32 shl 1, 33 shl 1, 34 shl 1), extracted)
    }

    @Test
    fun `extracted parameter sets always use four byte start codes`() {
        val au = byteArrayOf(0, 0, 1, 0x67, 0x11, 0, 0, 1, 0x68, 0x22, 0, 0, 1, 0x65, 0x33)
        val extracted = NalUnits.extractParameterSets(au, au.size, isH265 = false)!!
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x11, 0, 0, 0, 1, 0x68, 0x22),
            extracted,
        )
    }

    @Test
    fun `extraction respects the length limit`() {
        val au = annexB(0x67, 0x68, 0x65)
        // Only the SPS is visible within the first 7 bytes.
        assertArrayEquals(annexB(0x67), NalUnits.extractParameterSets(au, 7, isH265 = false))
    }

    @Test
    fun `sps pps idr access unit is still reported as a keyframe`() {
        val au = annexB(0x67, 0x68, 0x65)
        assertTrue(NalUnits.isKeyFrame(au, au.size, isH265 = false))
    }
}
