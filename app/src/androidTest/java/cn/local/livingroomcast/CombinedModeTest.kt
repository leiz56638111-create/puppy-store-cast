// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.MainActivity as AirPlayActivity
import io.github.jqssun.airplay.AirPlaySupport
import io.github.jqssun.airplay.ReceiverMode
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CombinedModeTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private fun <T> ui(task: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return task()
        var value: T? = null
        inst.runOnMainSync { value = task() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fail(label)
    }
    private fun current(): Activity? = ui {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    private fun receiver(activity: Activity): ReceiverService? = ui {
        MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }.get(activity) as? ReceiverService
    }
    private fun buttons(view: View): List<Button> = when(view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun click(activity: Activity, label: String) = ui {
        val button = buttons(activity.window.decorView).single { it.text.toString() == label || it.contentDescription?.toString() == label }
        assertTrue("Button $label is enabled", button.isEnabled); button.performClick()
    }
    private fun airPlayRunning(): Boolean = ui { (current() as? AirPlayActivity)?.service?.serverState?.value == AirPlayService.ServerState.RUNNING }
    private fun airPlayPortOpen(): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 7000), 500) }; true }.getOrDefault(false)
    private fun open(): MainActivity {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await("DLNA activity missing") { current() is MainActivity }
        val activity = current() as MainActivity
        await("DLNA HTTP service not ready") { receiver(activity)?.let { ui { it.ui().endpoint().isNotEmpty() } } == true }
        return activity
    }
    private fun capture(name: String) {
        inst.waitForIdleSync(); Thread.sleep(800)
        val folder=java.io.File(context.filesDir,"combined-tests").apply { mkdirs() }
        val bitmap=inst.uiAutomation.takeScreenshot()
        java.io.File(folder,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun notificationIds(): Set<Int> = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()
    private fun request(endpoint: String): String {
        val connection = URL("$endpoint/description.xml").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 3000
        try { assertEquals(200, connection.responseCode); return connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }
    @Before fun resetMode() { ReceiverMode.selectDlna(context) }
    @After fun close() {
        ReceiverMode.selectDlna(context)
        current()?.takeIf { it !is MainActivity }?.let { activity -> ui { activity.finish() } }
        scenario?.close()
        context.stopService(Intent(context, ReceiverService::class.java))
        context.stopService(Intent(context, AirPlayService::class.java))
        await("AirPlay leaked after close") { !airPlayPortOpen() }
    }
    @Test fun packageHasOneLauncherAndTheOriginalInstallIdentity() {
        open()
        assertEquals("cn.local.livingroomcast", context.packageName)
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        assertEquals(1, context.packageManager.queryIntentActivities(intent, 0).size)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("1.0.1", info.versionName)
        assertEquals(11L, info.longVersionCode)
        assertEquals(23, info.applicationInfo!!.minSdkVersion)
        assertFalse(AirPlaySupport.isSupported(23, arrayOf("arm64-v8a")))
        assertFalse(AirPlaySupport.isSupported(31, arrayOf("x86_64")))
        assertTrue(AirPlaySupport.isSupported(24, arrayOf("armeabi-v7a")))
    }
    @Test fun homeKeepsAirPlayAndLauncherRestoresItsMode() {
        val dlna = open()
        click(dlna,"AirPlay")
        await("mirror did not start") { current() is AirPlayActivity && airPlayRunning() }
        val old = (current() as AirPlayActivity).service
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        await("Home lost AirPlay") { airPlayPortOpen() && notificationIds() == setOf(1) && !ui { old!!.presentationVisible } }
        ui { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        await("launcher did not restore AirPlay") { current() is AirPlayActivity && airPlayRunning() }
        assertSame(old, (current() as AirPlayActivity).service)
        assertEquals(setOf(1), notificationIds())
        click(current()!!,"返回 DLNA")
        await("explicit switch did not restore DLNA") { current() is MainActivity && receiver(current()!!)?.let { ui { it.ui().endpoint().isNotEmpty() } } == true }
        await("AirPlay did not stop after switching") { !airPlayPortOpen() && notificationIds() == setOf(41) }
    }
    @Test fun repeatedlySwitchingModesReleasesThePreviousServiceAndPreservesIdentity() {
        val dlna = open()
        val preferences = context.getSharedPreferences("receiver", 0)
        capture("combined-home.png")
        val identity = preferences.getString("uuid", "")
        assertFalse(identity.isNullOrEmpty())
        repeat(3) { index ->
            val endpoint = ui { receiver(dlna)!!.ui().endpoint() }
            assertTrue(request(endpoint).contains(identity!!))
            assertFalse(airPlayRunning())
            assertEquals(setOf(41), notificationIds())
            click(dlna,"AirPlay")
            await("AirPlay activity did not open") { current() is AirPlayActivity }
            val mirror = current()!!
            await("AirPlay server not advertising") { airPlayRunning() && notificationIds() == setOf(1) }
            if (index == 1) { Thread.sleep(650); capture("combined-airplay.png") }
            assertFalse("Host must stay on stack for return", dlna.isFinishing)
            assertFalse("Old DLNA endpoint must stop", runCatching { request(endpoint); true }.getOrDefault(false))
            assertEquals(identity, preferences.getString("uuid", ""))
            if (index > 0) await("DLNA host did not reach stopped state") {
                ui { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).contains(dlna) }
            }
            click(mirror,"返回 DLNA")
            await("DLNA did not resume") { current() === dlna && receiver(dlna)?.let { ui { it.ui().endpoint().isNotEmpty() } } == true }
            await("AirPlay resources not released") { !airPlayPortOpen() && notificationIds() == setOf(41) }
            assertEquals(identity, preferences.getString("uuid", ""))
            assertEquals(1f, ui { receiver(dlna)!!.playback().speed() })
        }
    }
    @Test fun waitingRemoteNavigationRevealsActionsAndReturnsToModePicker() {
        val activity = open()
        val lobby = ui { MainActivity::class.java.getDeclaredField("waitingPane").apply { isAccessible = true }.get(activity) as io.github.jqssun.airplay.ui.NightLobby }
        fun key(code: Int) {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                inst.sendKeySync(android.view.KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD))
        }
        fun focusedAndVisible(button: Button): Boolean = ui {
            val rect=android.graphics.Rect()
            activity.currentFocus === button && button.getGlobalVisibleRect(rect) && rect.height() >= button.height * .9
        }
        ui { lobby.dlnaButton.requestFocus() }
        key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        await("Down must reach visible standby button") { focusedAndVisible(lobby.standbyButton) }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right must reach visible details button") { focusedAndVisible(lobby.detailsButton) }
        key(android.view.KeyEvent.KEYCODE_DPAD_UP)
        await("Up must return to visible mode picker") { focusedAndVisible(lobby.airplayButton) }
        capture("combined-navigation.png")
    }

}
