/* Based on 0xb1ob/air-tv, GPLv3. Chinese experiment UI and lifecycle changes:
 * Copyright (C) 2026 小狗便利店投屏 contributors. SPDX-License-Identifier: GPL-3.0-only */
package com.airtv.receiver.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.widget.*
import com.airtv.receiver.R
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.service.AirPlayService

/** Optional foreground-only AirPlay mode inside the combined DLNA host application. */
class MainActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private var receiver: AirPlayReceiver? = null
    private var pendingSurface: SurfaceHolder? = null
    private var bound = false
    private var dialog: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private val listener: (ReceiverState) -> Unit = { render(it) }
    private val tick = object : Runnable {
        override fun run() { receiver?.let { render(it.state) }; main.postDelayed(this, 400) }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AirPlayService.LocalBinder ?: return
            receiver = local.receiver
            pendingSurface?.let { local.receiver.setSurface(it.surface) }
            local.receiver.addListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            receiver?.removeListener(listener); receiver = null
            statusText.text = "镜像服务已停止，请重新打开应用"
            overlay.visibility = View.VISIBLE
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.airtv.receiver.airplay.MirrorSupport.isSupported(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)) {
            Toast.makeText(this, "本机暂不支持 AirPlay 镜像，请使用视频 App 投屏", Toast.LENGTH_LONG).show()
            finish(); return
        }
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        detailsText = findViewById(R.id.details)
        surfaceView.setOnClickListener { showControls() }
        findViewById<Button>(R.id.restart).setOnClickListener { restart() }
        findViewById<Button>(R.id.diagnostics).setOnClickListener { showControls() }
        findViewById<Button>(R.id.exit).setOnClickListener { confirmExit() }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { pendingSurface = holder; receiver?.setSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { pendingSurface = holder; receiver?.setSurface(holder.surface) }
            override fun surfaceDestroyed(holder: SurfaceHolder) { pendingSurface = null; receiver?.setSurface(null) }
        })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
    }
    override fun onStart() {
        super.onStart()
        if (!::surfaceView.isInitialized || isFinishing) return
        // The host's DLNA service must be gone before the mirror decoder starts.
        stopService(Intent().setClassName(this, "cn.local.livingroomcast.ReceiverService"))
        try {
            AirPlayService.start(this)
            bound = bindService(Intent(this, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
            main.post(tick)
        } catch (e: RuntimeException) { statusText.text = "镜像服务启动失败：${e.javaClass.simpleName}" }
    }
    override fun onStop() {
        if (!::surfaceView.isInitialized) { super.onStop(); return }
        dialog?.dismiss(); dialog = null
        main.removeCallbacksAndMessages(null)
        receiver?.removeListener(listener); receiver?.setSurface(null)
        if (bound) { unbindService(connection); bound = false }
        receiver = null
        AirPlayService.stop(this)
        super.onStop()
    }
    private fun restart() {
        receiver?.let { r -> r.stop(); r.start(); pendingSurface?.let { r.setSurface(it.surface) } }
    }
    private fun confirmExit() {
        if (dialog != null) return
        dialog = AlertDialog.Builder(this).setTitle("返回视频投屏？")
            .setMessage("将断开屏幕镜像，返回后恢复视频 App 的 TV／投屏接收。")
            .setPositiveButton("返回视频投屏") { _, _ -> finish() }.setNegativeButton("取消", null).create().also {
                it.setOnDismissListener { dialog = null }; it.show()
            }
    }
    private fun showControls() {
        if (dialog != null) return
        val r = receiver
        val message = buildString {
            append("小狗便利店投屏 0.1.8-test · AirPlay 镜像测试\n")
            append("设备：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}\n")
            append("状态：${statusText.text}\n")
            if (r != null) {
                append("已显示视频帧：${r.videoFramesRendered}\n丢弃视频帧：${r.videoFramesDropped}\n")
                append("${r.audioDiagnostics}\n")
                append("接收规格：最高 1920×1080 / 30 帧\n")
            }
            append("\n使用 iPhone／iPad 控制中心的“屏幕镜像”。实验版请保持界面打开，不提供视频 App 内 AirPlay 按钮兼容承诺。")
        }
        dialog = AlertDialog.Builder(this).setTitle("镜像连接详情").setMessage(message)
            .setPositiveButton("关闭", null).setNeutralButton(if (r?.state is ReceiverState.Streaming) "恢复声音" else "重新接收") { _, _ ->
                if (r?.state is ReceiverState.Streaming) {
                    val recovered = r.recoverAudio()
                    android.widget.Toast.makeText(this,
                        if (recovered) "已重建音频输出，请继续播放测试" else "尚未收到支持的音频格式，请查看连接详情",
                        android.widget.Toast.LENGTH_LONG).show()
                } else restart()
            }
            .setNegativeButton("返回视频投屏") { _, _ -> finish() }.create().also {
                it.setOnDismissListener { dialog = null }; it.show()
            }
    }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) { confirmExit(); return true }
        if (overlay.visibility != View.VISIBLE && code in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            showControls(); return true
        }
        return super.onKeyDown(code, event)
    }
    private fun applyVideoAspect(width: Int, height: Int) {
        val root = findViewById<View>(android.R.id.content)
        if (root.width <= 0 || root.height <= 0) { root.post { applyVideoAspect(width, height) }; return }
        val rect = VideoLayout.fitInside(width, height, root.width, root.height) ?: return
        val current = surfaceView.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (current?.width == rect.width && current.height == rect.height) return
        surfaceView.layoutParams = android.widget.FrameLayout.LayoutParams(rect.width, rect.height, Gravity.CENTER)
    }
    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Streaming -> {
                val ready = (receiver?.videoFramesRendered ?: 0) > 0
                overlay.visibility = if (ready) View.GONE else View.VISIBLE
                statusText.text = if (ready) "正在接收屏幕镜像" else "已连接，等待镜像画面…"
                detailsText.text = if (state.width > 0 && state.height > 0) "视频尺寸 ${state.width}×${state.height}" else "正在识别视频尺寸"
                applyVideoAspect(state.width, state.height)
            }
            is ReceiverState.Advertising -> {
                overlay.visibility = View.VISIBLE
                statusText.text = "等待 iPhone／iPad 屏幕镜像"
                detailsText.text = "选择“${state.name}”\n本机地址 ${state.address ?: "未获取，请检查家庭网络"}"
            }
            is ReceiverState.AudioOnly -> {
                overlay.visibility = View.VISIBLE
                statusText.text = "收到的是音频连接"
                detailsText.text = "请在控制中心选择“屏幕镜像”，以接收完整画面和声音。"
            }
            is ReceiverState.Failed -> { overlay.visibility = View.VISIBLE; statusText.text = state.reason; detailsText.text = "可点“重新接收”重试" }
            ReceiverState.Stopped -> { overlay.visibility = View.VISIBLE; statusText.text = "正在启动镜像接收…"; detailsText.text = "" }
        }
    }
}
