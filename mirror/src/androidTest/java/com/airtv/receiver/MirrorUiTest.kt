/* SPDX-License-Identifier: GPL-3.0-only; Copyright (C) 2026 小狗便利店投屏 contributors */
package com.airtv.receiver

import android.app.NotificationManager
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airtv.receiver.airplay.NativeAirPlay
import com.airtv.receiver.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirrorUiTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fail("timed out waiting for mirror lifecycle")
    }
    private fun open(): ActivityScenario<MainActivity> {
        return ActivityScenario.launch(MainActivity::class.java).also { scenario = it; await { NativeAirPlay.nativeIsRunning() } }
    }
    @After fun close() { scenario?.close(); await { !NativeAirPlay.nativeIsRunning() } }
    @Test fun mirrorUsesTheCombinedInstallPackage() {
        open().onActivity { assertEquals("cn.local.livingroomcast", it.packageName) }
    }
    @Test fun realTouchOpensTheConnectionDetails() {
        val position = IntArray(2)
        open().onActivity { activity ->
            val button = activity.findViewById<Button>(R.id.diagnostics)
            button.getLocationOnScreen(position); position[0] += button.width / 2; position[1] += button.height / 2
        }
        Thread.sleep(300)
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, position[0].toFloat(), position[1].toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("镜像连接详情")?.isNotEmpty() == true }
    }
    @Test fun theAudioRecoveryButtonKeepsTheSessionConnected() {
        lateinit var r: com.airtv.receiver.airplay.AirPlayReceiver
        val activity = open()
        await {
            var bound = false
            activity.onActivity { a ->
                val field = MainActivity::class.java.getDeclaredField("receiver").apply { isAccessible = true }
                (field.get(a) as? com.airtv.receiver.airplay.AirPlayReceiver)?.let { r = it; bound = true }
            }
            bound
        }
        activity.onActivity { a ->
            r.onSessionStart()
            r.onAudioFormat(4, usingScreen = true, isMedia = false)
            a.findViewById<Button>(R.id.diagnostics).performClick()
        }
        await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("恢复声音")?.isNotEmpty() == true }
        val button = instrumentation.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText("恢复声音").first()
        assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        await { r.audioDiagnostics.contains("音频重建：1") }
        assertTrue(r.state is com.airtv.receiver.airplay.ReceiverState.Streaming)
        assertTrue(NativeAirPlay.nativeIsRunning())
    }
    @Test fun leavingTheSurfaceStopsTheServerAndReopeningRestartsIt() {
        val activity = open()
        activity.moveToState(Lifecycle.State.CREATED)
        await { !NativeAirPlay.nativeIsRunning() }
        val notifications = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
        assertEquals(0, notifications.activeNotifications.size)
        activity.moveToState(Lifecycle.State.RESUMED)
        await { NativeAirPlay.nativeIsRunning() }
    }
}
