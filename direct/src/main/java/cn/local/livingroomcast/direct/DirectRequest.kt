// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct
import java.net.URI
object DirectRequest {
    fun accepted(location: String): Boolean = runCatching {
        val uri=URI(location)
        location.length <= 32768 && uri.scheme?.lowercase() in setOf("http","https") &&
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && !location.contains('\n') && !location.contains('\r')
    }.getOrDefault(false)
    fun startMillis(seconds: Float): Long = if(seconds.isFinite() && seconds>=0 && seconds<=604800f) (seconds*1000.0).toLong() else 0L
}
