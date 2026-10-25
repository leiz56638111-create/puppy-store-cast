// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct
import org.junit.Assert.*
import org.junit.Test
class DirectRequestTest {
 @Test fun standardUrlsAndLocalPlaylistProxyAreAccepted(){assertTrue(DirectRequest.accepted("https://example.invalid/video.mp4?token=synthetic"));assertTrue(DirectRequest.accepted("http://localhost:7000/master.m3u8"))}
 @Test fun nonNetworkAndCredentialsAreRejected(){for(url in listOf("file:///data/test","javascript:alert(1)","http://user:pass@example.invalid/a","https:///a","http://host/\n"))assertFalse(url,DirectRequest.accepted(url))}
 @Test fun invalidStartPositionsStayBounded(){assertEquals(0L,DirectRequest.startMillis(Float.NaN));assertEquals(0L,DirectRequest.startMillis(-1f));assertEquals(2500L,DirectRequest.startMillis(2.5f))}
}
