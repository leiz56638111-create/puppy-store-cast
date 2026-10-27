// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay

/** Does not load JNI, so the API23 DLNA host can safely check this first. */
object AirPlaySupport {
    @JvmStatic fun isSupported(api: Int, abis: Array<String>): Boolean =
        api >= 24 && abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
}
