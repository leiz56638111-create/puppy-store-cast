package com.airtv.receiver.ui

import kotlin.math.min
import kotlin.math.roundToInt

/** Size of the on-screen video rectangle, in pixels. */
data class VideoRect(val width: Int, val height: Int)

/** Letterboxing maths for the mirrored picture. */
object VideoLayout {

    /**
     * Largest rectangle with the source's aspect ratio that fits inside the screen.
     * Returns null when any dimension is not yet known.
     */
    fun fitInside(
        sourceWidth: Int,
        sourceHeight: Int,
        availableWidth: Int,
        availableHeight: Int,
    ): VideoRect? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (availableWidth <= 0 || availableHeight <= 0) return null
        val scale = min(
            availableWidth.toDouble() / sourceWidth,
            availableHeight.toDouble() / sourceHeight,
        )
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, availableWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, availableHeight)
        return VideoRect(width, height)
    }
}
