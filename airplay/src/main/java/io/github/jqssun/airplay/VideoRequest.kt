// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay

import java.net.URI

object VideoRequest {
    fun accepted(location: String): Boolean = runCatching {
        val uri = URI(location)
        location.length <= 32768 && uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && !location.contains('\n') && !location.contains('\r')
    }.getOrDefault(false)
    fun startSeconds(seconds: Float): Float = if (seconds.isFinite() && seconds in 0f..604800f) seconds else 0f
}
