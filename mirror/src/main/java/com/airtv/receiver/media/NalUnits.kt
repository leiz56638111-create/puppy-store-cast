package com.airtv.receiver.media

import java.io.ByteArrayOutputStream

/** Helpers for inspecting Annex-B H.264 / H.265 access units. */
object NalUnits {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun isCodecConfig(data: ByteArray, length: Int, isH265: Boolean): Boolean {
        var hasParameterSet = false
        var hasSlice = false
        forEachNalType(data, length, isH265) { type ->
            if (isParameterSet(type, isH265)) hasParameterSet = true
            if (isVideoSlice(type, isH265)) hasSlice = true
        }
        return hasParameterSet && !hasSlice
    }

    fun isKeyFrame(data: ByteArray, length: Int, isH265: Boolean): Boolean {
        var keyFrame = false
        forEachNalType(data, length, isH265) { type ->
            val isIrap = if (isH265) type in 16..21 else type == 5
            if (isIrap) keyFrame = true
        }
        return keyFrame
    }

    /**
     * Returns the parameter-set NAL units (H.264 SPS/PPS, H.265 VPS/SPS/PPS) found in an
     * access unit, as Annex-B with 4-byte start codes, or null if there are none.
     *
     * The AirPlay mirror protocol delivers the parameter sets prepended to a keyframe rather
     * than as a standalone access unit, so they have to be recoverable from a buffer that
     * also contains slices.
     */
    fun extractParameterSets(data: ByteArray, length: Int, isH265: Boolean): ByteArray? {
        var out: ByteArrayOutputStream? = null
        forEachNal(data, length, isH265) { type, payloadStart, payloadEnd ->
            if (isParameterSet(type, isH265) && payloadEnd > payloadStart) {
                val sink = out ?: ByteArrayOutputStream(64).also { out = it }
                sink.write(START_CODE)
                sink.write(data, payloadStart, payloadEnd - payloadStart)
            }
        }
        return out?.toByteArray()
    }

    fun describe(data: ByteArray, length: Int, isH265: Boolean): String {
        val types = ArrayList<Int>(4)
        forEachNalType(data, length, isH265) { types.add(it) }
        return types.joinToString(",")
    }

    /** H.264: SPS/PPS/SPS-ext/subset-SPS. H.265: VPS/SPS/PPS. */
    private fun isParameterSet(type: Int, isH265: Boolean): Boolean =
        if (isH265) type in 32..34 else type == 7 || type == 8 || type == 13 || type == 15

    private fun isVideoSlice(type: Int, isH265: Boolean): Boolean =
        if (isH265) type in 0..31 else type in 1..5

    private inline fun forEachNalType(
        data: ByteArray,
        length: Int,
        isH265: Boolean,
        action: (Int) -> Unit,
    ) = forEachNal(data, length, isH265) { type, _, _ -> action(type) }

    /**
     * Walks the access unit, reporting each NAL unit's type and the bounds of its payload
     * (the header byte through to the next start code, or the end of the buffer).
     */
    private inline fun forEachNal(
        data: ByteArray,
        length: Int,
        isH265: Boolean,
        action: (type: Int, payloadStart: Int, payloadEnd: Int) -> Unit,
    ) {
        val limit = minOf(length, data.size)
        var i = 0
        var currentType = -1
        var currentStart = -1
        while (i + 2 < limit) {
            val startCodeLength = startCodeLengthAt(data, i, limit)
            if (startCodeLength == 0) {
                i++
                continue
            }
            if (currentStart >= 0) action(currentType, currentStart, i)
            val headerIndex = i + startCodeLength
            if (headerIndex < limit) {
                val header = data[headerIndex].toInt() and 0xFF
                currentType = if (isH265) (header shr 1) and 0x3F else header and 0x1F
                currentStart = headerIndex
            } else {
                currentType = -1
                currentStart = -1
            }
            i += startCodeLength
        }
        if (currentStart >= 0) action(currentType, currentStart, limit)
    }

    private fun startCodeLengthAt(data: ByteArray, i: Int, limit: Int): Int {
        if (data[i] != 0.toByte() || data[i + 1] != 0.toByte()) return 0
        if (data[i + 2] == 1.toByte()) return 3
        if (i + 3 < limit && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return 4
        return 0
    }
}
