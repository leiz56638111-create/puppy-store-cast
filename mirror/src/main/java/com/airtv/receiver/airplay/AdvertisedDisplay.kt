package com.airtv.receiver.airplay

import kotlin.math.min
import kotlin.math.roundToInt

/** The display geometry we tell the AirPlay client about. */
data class AdvertisedDisplay(val width: Int, val height: Int, val fps: Int) {

    companion object {
        const val MAX_WIDTH = 3840
        const val MAX_HEIGHT = 2160
        private const val MIN_FPS = 24
        private const val MAX_FPS = 60
        private val FALLBACK = AdvertisedDisplay(1920, 1080, MAX_FPS)

        fun from(width: Int, height: Int, refreshRate: Float): AdvertisedDisplay {
            val fps = when {
                refreshRate <= 0f -> MAX_FPS
                else -> refreshRate.roundToInt().coerceIn(MIN_FPS, MAX_FPS)
            }
            if (width <= 0 || height <= 0) return FALLBACK.copy(fps = fps)

            // Mirroring is always landscape; a portrait report means we got the metrics
            // before the TV surface settled.
            var w = maxOf(width, height)
            var h = min(width, height)

            if (w > MAX_WIDTH || h > MAX_HEIGHT) {
                val scale = min(MAX_WIDTH.toDouble() / w, MAX_HEIGHT.toDouble() / h)
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            return AdvertisedDisplay(w and 1.inv(), h and 1.inv(), fps)
        }
    }
}
