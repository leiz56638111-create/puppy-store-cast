// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.Button
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.service.AirPlayService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReferenceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var service: AirPlayService
    private val clients = mutableListOf<Wire>()
    private fun <T> ui(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null; inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(label: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; Thread.sleep(100) }
        fail(label + "; " + runCatching { ui { service.comparisonDetails() } }.getOrDefault("no service"))
    }
    @Before fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("receiver running") { var found = false; scenario.onActivity { it.service?.let { s -> service = s; found = s.serverState.value == AirPlayService.ServerState.RUNNING } }; found }
    }
    @After fun close() { clients.forEach { runCatching { it.socket.close() } }; scenario.close() }
    private fun player(): ExoPlayer? = ui {
        service.airPlayVideoPlayer.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(service.airPlayVideoPlayer) as? ExoPlayer
    }
    private fun handle(): Long = ui { service.javaClass.getDeclaredField("nativeHandle").apply { isAccessible = true }.getLong(service) }
    private fun view(name: String): View { var v: View? = null; scenario.onActivity { v = it.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(it) as View }; return v!! }
    private fun client() = Wire(Socket("127.0.0.1", 7000).apply { soTimeout = 12000 }).also { clients += it }
    data class Reply(val status: Int, val body: ByteArray)
    class Wire(val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private fun line(): String { val b = java.io.ByteArrayOutputStream(); while (true) { val v = input.read(); if (v < 0) throw java.io.EOFException(); if (v == 10) break; if (v != 13) b.write(v) }; return b.toString("UTF-8") }
        fun read(): Reply { val first = line(); var len = 0; while (true) { val s = line(); if (s.isEmpty()) break; if (s.startsWith("Content-Length:", true)) len = s.substringAfter(':').trim().toInt() }; val body = ByteArray(len); var n = 0; while (n < len) { val read = input.read(body, n, len - n); if (read < 0) throw java.io.EOFException(); n += read }; return Reply(if (first.startsWith("HTTP/") || first.startsWith("RTSP/")) first.split(' ')[1].toInt() else 0, body) }
        fun send(method: String, path: String, body: ByteArray = byteArrayOf(), type: String = "text/parameters", session: String = "11111111-2222-3333-4444-555555555555", headers: String = ""): Reply {
            val head = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Apple-Session-ID: $session\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$headers\r\n"
            socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }; return read()
        }
    }
    private fun asset(name: String) = inst.context.assets.open(name).use { it.readBytes() }
    private fun play(w: Wire, path: String = "http://10.0.2.2:18099/video.mp4", session: String = "11111111-2222-3333-4444-555555555555") {
        assertEquals(200, w.send("POST", "/play", "Content-Location: $path\r\nStart-Position: 0\r\n".toByteArray(), session = session).status)
        await("decoded video and audio") { val p = player(); p != null && ui { p.isPlaying && service.airPlayVideoPlayer.renderedFirstFrame && (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } }
    }
    private fun screenshot(name: String) { inst.waitForIdleSync(); Thread.sleep(500); val dir = File(inst.targetContext.filesDir, "reference-tests").apply { mkdirs() }; val b = inst.uiAutomation.takeScreenshot(); File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
    private fun pixelCopy(name: String) {
        val b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        var status = PixelCopy.ERROR_SOURCE_NO_DATA
        val end = SystemClock.elapsedRealtime() + 5000
        while (status == PixelCopy.ERROR_SOURCE_NO_DATA && SystemClock.elapsedRealtime() < end) {
            val done = CountDownLatch(1)
            ui { PixelCopy.request(view(name) as SurfaceView, b, { status = it; done.countDown() }, Handler(Looper.getMainLooper())) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            if (status == PixelCopy.ERROR_SOURCE_NO_DATA) Thread.sleep(100)
        }
        assertEquals(PixelCopy.SUCCESS, status)
        val pixels = IntArray(320 * 180); b.getPixels(pixels, 0, 320, 0, 0, 320, 180)
        val lit = pixels.count { maxOf((it ushr 16) and 255, (it ushr 8) and 255, it and 255) > 64 }
        assertTrue("actual surface has bright decoded content", lit > 1000)
        assertTrue("actual surface is not a blank solid color", pixels.distinct().size > 1)
        val dir = File(inst.targetContext.filesDir, "reference-tests").apply { mkdirs() }; File(dir, "$name-pixelcopy.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
    }
    private fun press(code: Int) {
        await("input focus") { var ready = false; scenario.onActivity { a -> val popup = a.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(a) as? AlertDialog; ready = if (popup?.isShowing == true) popup.window!!.decorView.hasWindowFocus() else a.hasWindowFocus() }; ready }
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) inst.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD))
        inst.waitForIdleSync()
    }
    private fun label(): String { var s = ""; scenario.onActivity { s = (it.window.decorView.findFocus() as? Button)?.text?.toString() ?: "none" }; return s }
    private fun popup(): Boolean { var showing = false; scenario.onActivity { showing = (it.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(it) as? AlertDialog)?.isShowing == true }; return showing }

    @Test fun independentIdentityAndUpstreamDefaults() {
        assertEquals("cn.local.livingroomcast.airplayreference", inst.targetContext.packageName)
        val h = handle(); val raop = NativeBridge.nativeGetRaopTxtRecords(h)!!
        assertEquals("0,2,3", raop["cn"]); assertEquals("小狗便利店投屏·上游对照", NativeBridge.nativeGetServerName(h))
        val reply = client().send("GET", "/server-info"); assertEquals(200, reply.status)
        assertTrue(String(reply.body).contains("<integer>639</integer>")) // upstream legacy server-info mask 0x27f
        assertEquals(60, Prefs.MAX_FPS.def); assertFalse(Prefs.ALAC_ENABLED.def)
        assertFalse(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE.def)
        assertTrue(ui { service.comparisonDetails() }.contains("非显示帧率")); screenshot("waiting.png")
    }
    @Test fun remoteWaitingDetailsRestartAndAudioOnly() {
        await("initial focus") { label() == "连接详情" }
        press(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("重新连接", label())
        press(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("退出测试", label())
        press(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals("重新连接", label())
        press(KeyEvent.KEYCODE_DPAD_CENTER); await("restart works") { ui { service.serverState.value == AirPlayService.ServerState.RUNNING } }
        press(KeyEvent.KEYCODE_DPAD_UP); assertEquals("连接详情", label())
        press(KeyEvent.KEYCODE_DPAD_CENTER); assertTrue(popup()); screenshot("details.png")
        press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_CENTER); assertFalse(popup())
        service.onAudioFormat(2, 352, false)
        await("ALAC audio engine opened") { ui { service.audioRenderer.codecLabel == "ALAC" && service.audioRenderer.audioDebug() != null } }
        assertFalse(service.mirroringActive.value); assertFalse(service.videoPlaybackActive.value)
        assertTrue(ui { service.comparisonDetails() }.contains("ct=2 / spf=352 / usingScreen=false"))
        assertEquals("连接详情", label())
    }
    @Test fun mp4ActualSurfaceAudioRemotePauseSeekStop() {
        val w = client(); play(w); pixelCopy("video"); screenshot("mp4.png")
        val first = ui { player()!!.currentPosition }; Thread.sleep(700); assertTrue(ui { player()!!.currentPosition } > first + 250)
        press(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals("连接详情", label())
        press(KeyEvent.KEYCODE_DPAD_CENTER); assertTrue(popup()); press(KeyEvent.KEYCODE_DPAD_CENTER); assertFalse(popup())
        press(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("暂停", label()); press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remote pause") { !service.videoPlaybackInfo.value.playing }
        assertEquals(200, w.send("POST", "/scrub?position=12").status)
        await("seek paused") { ui { kotlin.math.abs(player()!!.currentPosition - 12000) < 800 && !player()!!.playWhenReady } }
        press(KeyEvent.KEYCODE_DPAD_CENTER); await("remote resumes") { service.videoPlaybackInfo.value.playing }
        assertEquals(200, w.send("POST", "/stop").status); await("stopped") { !service.videoPlaybackActive.value && player() == null }
    }
    @Test fun ordinaryHlsMasterIsDirectAndSessionReplacementIsSafe() {
        val w = client(); play(w, "http://10.0.2.2:18099/master.m3u8")
        play(w, "http://10.0.2.2:18099/video.mp4?second=1", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(ui { player()!!.currentMediaItem!!.localConfiguration!!.uri.toString() }.endsWith("?second=1"))
        assertEquals(409, w.send("POST", "/stop").status)
        assertEquals(400, w.send("POST", "/rate?value=nan", session = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").status)
        assertTrue(ui { player()!!.isPlaying }); assertFalse(ui { service.comparisonDetails() }.contains("http://"))
        screenshot("hls-and-session.png")
    }
    @Test fun malformedRequestsDoNotCrashReceiver() {
        val w = client()
        assertEquals(400, w.send("POST", "/play", "bad-data".toByteArray()).status)
        assertEquals(400, w.send("POST", "/play", byteArrayOf(1, 2, 3), "application/x-apple-binary-plist").status)
        assertEquals(400, w.send("POST", "/play", "Content-Location: http://example.invalid/\r\n".toByteArray(), session = "x".repeat(129)).status)
        assertEquals(400, w.send("POST", "/rate?").status)
        play(w)
    }
    @Test fun reverseHlsPlaylistsReachRealPlayback() {
        val reverse = client(); assertEquals(101, reverse.send("POST", "/reverse", headers = "Connection: Upgrade\r\nUpgrade: PTTH/1.0\r\nX-Apple-Purpose: event\r\n", session = "test-session").status)
        val control = client(); assertEquals(200, control.send("POST", "/play", asset("proxy-play.bplist"), "application/x-apple-binary-plist", "test-session").status)
        assertTrue(String(reverse.read().body).contains("master.m3u8"))
        assertEquals(200, control.send("POST", "/action", asset("proxy-master.bplist"), "application/x-apple-binary-plist", "test-session").status)
        assertTrue(String(reverse.read().body).contains("variant.m3u8"))
        assertEquals(200, control.send("POST", "/action", asset("proxy-media.bplist"), "application/x-apple-binary-plist", "test-session").status)
        await("proxied HLS decoded") { val p = player(); p != null && ui { p.isPlaying && service.airPlayVideoPlayer.renderedFirstFrame && (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } }
        pixelCopy("video"); screenshot("reverse-hls.png")
    }
    @Test fun persistentMirrorSurfaceRendersAndRemoteEnds() {
        val units = H264TestStream.asAirPlayShaped(H264TestStream.encode(320, 180, 24))
        service.onMirrorRunning(true); service.onVideoSize(320f, 180f, 320f, 180f)
        await("mirror surface") { ui { view("mirror").visibility == View.VISIBLE && (view("mirror") as SurfaceView).holder.surface.isValid } }
        fun feed(offset: Long) { for (unit in units) { service.onVideoData(unit.bytes, System.nanoTime() + offset + unit.presentationTimeUs * 1000, false); Thread.sleep(30) } }
        feed(0); pixelCopy("mirror"); screenshot("mirror.png")
        val codec = service.videoRenderer.javaClass.getDeclaredField("codec").apply { isAccessible = true }.get(service.videoRenderer)
        ui { view("mirror").visibility = View.INVISIBLE }; Thread.sleep(250)
        ui { view("mirror").visibility = View.VISIBLE }; Thread.sleep(500); feed(1000000000)
        assertSame("display surface replacement retains decoder", codec, service.videoRenderer.javaClass.getDeclaredField("codec").apply { isAccessible = true }.get(service.videoRenderer))
        pixelCopy("mirror")
        press(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals("连接详情", label())
        press(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("结束接收", label()); press(KeyEvent.KEYCODE_DPAD_CENTER)
        await("end returns to waiting") { !service.mirroringActive.value && label() == "连接详情" }
    }
    @Test fun bonjourReplyContainsComparisonName() {
        val group = java.net.InetAddress.getByName("224.0.0.251")
        java.net.MulticastSocket(null).use { socket ->
            socket.reuseAddress = true; socket.bind(java.net.InetSocketAddress(5353)); socket.soTimeout = 1000
            @Suppress("DEPRECATION") socket.joinGroup(group)
            val bytes = java.io.ByteArrayOutputStream(); repeat(12) { bytes.write(if (it == 5) 1 else 0) }
            for (part in listOf("_airplay", "_tcp", "local")) { bytes.write(part.length); bytes.write(part.toByteArray()) }
            bytes.write(0); bytes.write(byteArrayOf(0, 12, 0, 1))
            val end = SystemClock.elapsedRealtime() + 10000; var found = false
            while (SystemClock.elapsedRealtime() < end && !found) {
                socket.send(java.net.DatagramPacket(bytes.toByteArray(), bytes.size(), group, 5353))
                try { val data = ByteArray(8192); val reply = java.net.DatagramPacket(data, data.size); socket.receive(reply); found = String(data, 0, reply.length, Charsets.UTF_8).contains("小狗便利店投屏·上游对照") } catch (_: java.net.SocketTimeoutException) { }
            }
            assertTrue("Bonjour must publish comparison receiver name", found)
        }
    }
    @Test fun homeStopsAndReopenStarts() {
        val old = service
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        await("Home stops receiver") { old.serverState.value == AirPlayService.ServerState.STOPPED }
        assertEquals(0, old.connectionCount.value)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await("reopen starts receiver") { var ready = false; scenario.onActivity { ready = it.service?.serverState?.value == AirPlayService.ServerState.RUNNING }; ready }
    }
}
