// Copyright (C) 2026 小狗便利店投屏 contributors
// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.provider.Settings
import android.net.Uri
import io.github.jqssun.airplay.ui.NightLobby
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

/** Native TV shell around the v0.0.36 service. Optional AirPlay mode of the DLNA host; no legacy :mirror dependency. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    var service: AirPlayService? = null; private set
    private var bound = false
    private lateinit var root: FrameLayout
    private lateinit var mirror: SurfaceView
    private lateinit var video: SurfaceView
    private lateinit var waiting: NightLobby
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
            if (!foreground || isFinishing) return
            service = (binder as AirPlayService.LocalBinder).service
            if (mirror.holder.surface.isValid) service?.setVideoSurface(mirror.holder.surface)
            if (video.holder.surface.isValid) service?.setVideoPlaybackSurface(video.holder.surface)
            render()
            attachVisiblePresentation()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; render() }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(0xFFEFF6F3.toInt()); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(label: String, action: () -> Unit) = NightLobby.button(this, label, false) { action() }.apply {
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
        root = FrameLayout(this).apply { setBackgroundColor(NightLobby.INK) }; setContentView(root)
        fun surface(playback: Boolean) = SurfaceView(this).apply {
            visibility = View.INVISIBLE; isFocusable = false
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (playback) service?.setVideoPlaybackSurface(holder.surface) else service?.setVideoSurface(holder.surface)
                    if (this@MainActivity.foreground) attachVisiblePresentation()
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
        waiting = NightLobby(this, true, ::returnToDlna, ::standby, ::details, { service?.restartReceiver() }, ::confirmExit)
        waiting.dlnaButton.contentDescription = "返回 DLNA"
        waiting.airplayButton.contentDescription = "AirPlay"
        waitingDetails = waiting.detailsButton
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        tools = LinearLayout(this).apply { visibility = View.GONE; setPadding(dp(12), dp(8), dp(12), dp(8)); background = NightLobby.shape(this@MainActivity, 0xee192927.toInt(), 16, 0) }
        playbackDetails = button("连接详情") { details() }
        pause = button("暂停") { service?.let { it.setVideoPlaying(!it.videoPlaybackInfo.value.playing) }; armHide() }
        val end = button("结束接收") { service?.restartReceiver() }
        listOf(playbackDetails, pause, end).forEachIndexed { index, b ->
            tools.addView(b, LinearLayout.LayoutParams(-2, dp(48)).apply { if (index > 0) leftMargin = dp(10) })
        }
        root.addView(tools, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }
    override fun onStart() {
        super.onStart()
        if (!AirPlaySupport.isSupported(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)) { finish(); return }
        foreground = true
        ReceiverMode.selectAirPlay(this)
        // The host may have been resumed from a notification; stop its receiver first.
        stopService(Intent().setClassName(this, "cn.local.livingroomcast.ReceiverService"))
        val intent = Intent(this, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        main.post(tick)
    }
    private fun attachVisiblePresentation() {
        val s = service ?: return
        val ready = when {
            s.videoPlaybackActive.value -> video.holder.surface.isValid && video.visibility == View.VISIBLE
            s.mirroringActive.value -> mirror.holder.surface.isValid && mirror.visibility == View.VISIBLE
            else -> true
        }
        if (foreground && ready) s.setPresentationVisible(true)
    }
    private fun detachPresentation() {
        foreground = false
        dialog?.dismiss(); dialog = null
        main.removeCallbacksAndMessages(null)
        if (mirror.holder.surface.isValid) service?.clearVideoSurface(mirror.holder.surface)
        if (video.holder.surface.isValid) service?.clearVideoPlaybackSurface(video.holder.surface)
        service?.setPresentationVisible(false)
        if (bound) { unbindService(connection); bound = false }
        service = null
        mode = ""; revealKey = null
    }
    private fun returnToDlna() {
        ReceiverMode.selectDlna(this)
        service?.stopServer()
        stopService(Intent(this, AirPlayService::class.java))
        detachPresentation()
        finish()
    }
    private fun confirmExit() {
        dialog = AlertDialog.Builder(this).setTitle("退出接收？")
            .setMessage("将停止 AirPlay 后台待机。再次打开应用即可恢复。")
            .setNegativeButton("继续接收", null)
            .setPositiveButton("退出") { _, _ ->
                ReceiverMode.stopAirPlay(this)
                service?.stopServer()
                stopService(Intent(this, AirPlayService::class.java))
                detachPresentation()
                finishAffinity()
            }.create().apply { setOnDismissListener { dialog = null }; show() }
    }
    private fun standby() {
        val allowed = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)
        val builder = AlertDialog.Builder(this).setTitle("AirPlay 后台待机")
            .setMessage(if (allowed) "回到桌面后继续等待 AirPlay，收到投屏时会尝试打开播放界面。关机、深度休眠或强制停止后，需重新打开应用。" else "回到桌面后仍可被 iPhone / iPad 发现。允许“显示在其他应用上层”后，投屏时会尝试自动打开界面；否则需手动打开应用，必要时重新投送。此权限不显示悬浮窗。")
            .setNegativeButton("取消", null)
        if (allowed) builder.setPositiveButton("进入后台") { _, _ -> moveTaskToBack(true) }
        else builder.setPositiveButton("去授权") { _, _ ->
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "可到系统应用设置中授权；也可手动打开本应用播放", Toast.LENGTH_LONG).show() }
        }.setNeutralButton("直接后台等待") { _, _ -> moveTaskToBack(true) }
        dialog = builder.create().apply { setOnDismissListener { dialog = null }; show() }
    }
    override fun onStop() { detachPresentation(); super.onStop() }
    private val tick = object : Runnable {
        override fun run() { if (foreground) { render(); main.postDelayed(this, 400) } }
    }
    private fun render() {
        val s = service
        if (s != null && !ReceiverMode.wantsAirPlay(this) && s.serverState.value == AirPlayService.ServerState.STOPPED) { finishAffinity(); return }
        val next = when { s?.videoPlaybackActive?.value == true -> "视频地址"; s?.mirroringActive?.value == true -> "视频流"; else -> "等待" }
        val changed = next != mode; mode = next
        mirror.visibility = if (mode == "视频流") View.VISIBLE else View.INVISIBLE
        video.visibility = if (mode == "视频地址") View.VISIBLE else View.INVISIBLE
        waiting.visibility = if (mode == "等待") View.VISIBLE else View.GONE
        val ready = s?.serverState?.value == AirPlayService.ServerState.RUNNING
        val message = when {
            s?.serverState?.value == AirPlayService.ServerState.ERROR -> "启动失败，请退出其他接收器，再点“重新连接”"
            s?.audioOnly?.value == true -> "已收到音频会话，等待视频画面"
            !ready -> "正在准备接收服务…"
            else -> ""
        }
        waiting.state(if (ready) "● 已就绪 · 等待连接" else "正在启动…", message, ready)
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
            if (mode == "等待") waiting.focusDefault() else root.requestFocus()
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { if (mode != "等待") service?.restartReceiver() else standby() }
            return true
        }
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
        val body = text(service?.connectionDetails() ?: "接收服务尚未启动", 16f).apply { setPadding(dp(20), dp(10), dp(20), dp(10)); isFocusable = false }
        val scroll = ScrollView(this).apply { isFocusable = false; addView(body) }
        dialog = AlertDialog.Builder(this).setTitle("AirPlay · 连接详情").setView(scroll).setPositiveButton("关闭", null).create().apply {
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
            setOnDismissListener { dialog = null; if (foreground) { if (mode == "等待") waitingDetails.requestFocus() else { showTools(true) } } }
            show()
        }
    }
}
