// SPDX-License-Identifier: GPL-3.0-only
package com.airtv.receiver.airplay

/** The bundled native dependencies target API 24 and these two ARM ABIs. */
object MirrorSupport {
    @JvmStatic fun isSupported(sdk: Int, abis: Array<String>): Boolean =
        sdk >= 24 && abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
}
