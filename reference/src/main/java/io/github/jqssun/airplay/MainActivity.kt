// Copyright (C) 2026 小狗便利店投屏 contributors
// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService
import java.net.Inet4Address
import java.net.NetworkInterface

/** Native TV shell around the v0.0.36 service. This app has no :mirror dependency. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    internal var service: AirPlayService? = null; private set
    private var bound = false
    private lateinit var root: FrameLayout
    private lateinit var mirror: SurfaceView
    private lateinit var video: SurfaceView
    private lateinit var waiting: ScrollView
    private lateinit var status: TextView
    private lateinit var tools: LinearLayout
    private lateinit var pause: Button
    private lateinit var waitingDetails: Button
    private lateinit var playbackDetails: Button
    private var dialog: AlertDialog? = null
    private var mode = ""
    private var lastAspect = 0f
    private var foreground = false
    private var revealKey: Int? = null
    private val select = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
    private val directions = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    private val hideTools = Runnable { if (dialog == null) tools.visibility = View.GONE }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!foreground) return
            service = (binder as AirPlayService.LocalBinder).service
            if (mirror.holder.surface.isValid) service?.setVideoSurface(mirror.holder.surface)
            if (video.holder.surface.isValid) service?.setVideoPlaybackSurface(video.holder.surface)
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; render() }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(0xFFEFF6F3.toInt()); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId(); text = label
        isFocusable = true; isFocusableInTouchMode = true; minHeight = dp(52)
        setPadding(dp(18), dp(8), dp(18), dp(8)); backgroundTintList = null
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(6).toFloat(); setStroke(dp(2), 0xFFCCEFE0.toInt())
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF8CE8C3.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF8CE8C3.toInt()))
            addState(intArrayOf(), shape(0xFF3F4C55.toInt()))
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE)))
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused -> if (focused && mode != "等待") armHide() }
    }
    private fun link(buttons: List<Button>) = buttons.forEachIndexed { i, b ->
        val previous = buttons[(i + buttons.size - 1) % buttons.size].id
        val next = buttons[(i + 1) % buttons.size].id
        b.nextFocusLeftId = previous; b.nextFocusUpId = previous
        b.nextFocusRightId = next; b.nextFocusDownId = next
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(0xFF101B20.toInt()) }; setContentView(root)
        fun surface(playback: Boolean) = SurfaceView(this).apply {
            visibility = View.INVISIBLE; isFocusable = false
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (playback) service?.setVideoPlaybackSurface(holder.surface) else service?.setVideoSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (playback) service?.clearVideoPlaybackSurface(holder.surface) else service?.clearVideoSurface(holder.surface)
                }
            })
            setOnClickListener { showTools(false) }
            root.addView(this, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        mirror = surface(false); video = surface(true)
        waiting = ScrollView(this).apply { isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(36), dp(20), dp(36), dp(20)) }
        waiting.addView(column); root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        column.addView(text("小狗便利店投屏 · 上游对照", 28f))
        column.addView(text("在相册或视频 App 内点隔空播放，选择“小狗便利店投屏·上游对照”", 20f))
        column.addView(text("也可从 iPhone／iPad 控制中心选择“屏幕镜像”。", 17f))
        status = text("正在启动…", 18f).apply { setTextColor(0xFF8CE8C3.toInt()) }; column.addView(status)
        column.addView(text("独立测试版 0.4.0-upstream-alpha1 · 上游内核 v0.0.36\n保持界面打开；测试前请退出其他投屏接收器。", 15f))
        val row = LinearLayout(this)
        waitingDetails = button("连接详情") { details() }
        val restart = button("重新连接") { service?.restartForComparison() }
        val exit = button("退出测试") { finish() }
        listOf(waitingDetails, restart, exit).forEach { row.addView(it) }; link(listOf(waitingDetails, restart, exit))
        column.addView(HorizontalScrollView(this).apply { isFocusable = false; addView(row) })
        column.addView(text("方向键选择，确认键打开；播放时按确认键呼出操作。Home／退出会停止接收。", 14f))
        tools = LinearLayout(this).apply { visibility = View.GONE; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0xDD101B20.toInt()) }
        playbackDetails = button("连接详情") { details() }
        pause = button("暂停") { service?.let { it.setVideoPlaying(!it.videoPlaybackInfo.value.playing) }; armHide() }
        val end = button("结束接收") { service?.restartForComparison() }
        tools.addView(playbackDetails); tools.addView(pause); tools.addView(end)
        root.addView(tools, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }
    override fun onStart() {
        super.onStart(); foreground = true
        val intent = Intent(this, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        main.post(tick)
    }
    override fun onStop() {
        foreground = false; main.removeCallbacksAndMessages(null)
        dialog?.dismiss(); dialog = null
        service?.stopServer(); service = null
        if (bound) { unbindService(connection); bound = false }
        stopService(Intent(this, AirPlayService::class.java))
        mode = ""; super.onStop()
    }
    private val tick = object : Runnable {
        override fun run() { if (foreground) { render(); main.postDelayed(this, 400) } }
    }
    private fun address(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }?.hostAddress
    }.getOrNull() ?: "未取得局域网地址"
    private fun render() {
        val s = service
        val next = when { s?.videoPlaybackActive?.value == true -> "视频地址"; s?.mirroringActive?.value == true -> "视频流"; else -> "等待" }
        val changed = next != mode; mode = next
        mirror.visibility = if (mode == "视频流") View.VISIBLE else View.INVISIBLE
        video.visibility = if (mode == "视频地址") View.VISIBLE else View.INVISIBLE
        waiting.visibility = if (mode == "等待") View.VISIBLE else View.GONE
        val audio = if (s?.audioOnly?.value == true) "已连接音频，尚未收到视频" else "等待 AirPlay"
        status.text = when (s?.serverState?.value) {
            AirPlayService.ServerState.RUNNING -> "$audio · 本机 ${address()}:7000 · 连接 ${s.connectionCount.value}"
            AirPlayService.ServerState.ERROR -> "启动失败，请先退出其他接收器，再点“重新连接”"
            else -> "正在启动接收服务…"
        }
        pause.text = if (s?.videoPlaybackInfo?.value?.playing == false) "继续" else "暂停"
        pause.visibility = if (mode == "视频地址") View.VISIBLE else View.GONE
        val visibleButtons = (0 until tools.childCount).map { tools.getChildAt(it) }.filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
        link(visibleButtons)
        val aspect = if (mode == "视频地址") s?.videoPlaybackAspect?.value ?: (16f / 9f) else s?.videoAspect?.value ?: (16f / 9f)
        if (mode != "等待" && root.width > 0 && root.height > 0 && (changed || lastAspect != aspect)) {
            lastAspect = aspect
            val width = minOf(root.width, (root.height * aspect).toInt()).coerceAtLeast(1)
            val height = minOf(root.height, (width / aspect).toInt()).coerceAtLeast(1)
            (if (mode == "视频地址") video else mirror).layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        if (changed) {
            tools.visibility = View.GONE
            if (mode == "等待") waitingDetails.requestFocus() else root.requestFocus()
        }
    }
    private fun armHide() { main.removeCallbacks(hideTools); if (mode != "等待") main.postDelayed(hideTools, 6000) }
    private fun showTools(focus: Boolean) {
        if (mode == "等待") return
        tools.visibility = View.VISIBLE
        if (focus) playbackDetails.requestFocus()
        armHide()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (revealKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) revealKey = null
            return true
        }
        if (dialog?.isShowing == true) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) details(); return true
        }
        if (mode != "等待" && (event.keyCode in select || event.keyCode in directions)) {
            if (tools.visibility != View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_DOWN) { revealKey = event.keyCode; showTools(true) }
                return true
            }
            armHide()
        }
        if (event.keyCode in select && currentFocus is Button) {
            if (event.action == KeyEvent.ACTION_UP) currentFocus?.performClick()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.action == KeyEvent.ACTION_UP) {
            if (mode == "视频地址") service?.let { it.setVideoPlaying(!it.videoPlaybackInfo.value.playing) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun details() {
        if (dialog?.isShowing == true) return
        main.removeCallbacks(hideTools)
        val body = text(service?.comparisonDetails() ?: "接收服务尚未启动", 16f).apply { setPadding(dp(20), dp(10), dp(20), dp(10)); isFocusable = false }
        val scroll = ScrollView(this).apply { isFocusable = false; addView(body) }
        dialog = AlertDialog.Builder(this).setTitle("AirPlay 上游对照 · 连接详情").setView(scroll).setPositiveButton("关闭", null).create().apply {
            setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE).apply { isFocusableInTouchMode = true; requestFocus() } }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, if (code == KeyEvent.KEYCODE_DPAD_UP) -dp(100) else dp(100))
                    true
                } else if (code in select) {
                    if (event.action == KeyEvent.ACTION_UP) dismiss()
                    true
                } else false
            }
            setOnDismissListener { dialog = null; if (mode == "等待") waitingDetails.requestFocus() else { showTools(true) } }
            show()
        }
    }
}
