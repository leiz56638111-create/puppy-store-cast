package com.airtv.receiver.service

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airtv.receiver.R
import com.airtv.receiver.ui.MainActivity
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState

/** Foreground mirror receiver; the activity explicitly stops it when leaving. */
class AirPlayService : Service() {

    inner class LocalBinder : Binder() {
        val receiver: AirPlayReceiver get() = this@AirPlayService.receiver
    }

    private val binder = LocalBinder()
    private lateinit var receiver: AirPlayReceiver
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    private val stateListener: (ReceiverState) -> Unit = { state ->
        if (started) {
            notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    override fun onCreate() {
        super.onCreate()
        receiver = AirPlayReceiver(applicationContext)
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification(receiver.state))
            started = true
            receiver.addListener(stateListener)
            if (!receiver.start()) {
                Log.e(TAG, "AirPlay receiver failed to start")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        receiver.removeListener(stateListener)
        receiver.setSurface(null)
        receiver.stop()
        releaseMulticastLock()
        started = false
        super.onDestroy()
    }

    /**
     * mDNS uses multicast; without this lock Wi-Fi power saving can silently drop the
     * queries that make the receiver discoverable.
     */
    private fun acquireMulticastLock() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "could not acquire multicast lock", it) }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun notificationManager() =
        getSystemService(NotificationManager::class.java) as NotificationManager

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(state: ReceiverState): Notification {
        val text = when (state) {
            is ReceiverState.Streaming -> getString(R.string.status_streaming, state.clientName)
            is ReceiverState.AudioOnly -> getString(R.string.status_audio_only, state.clientName)
            is ReceiverState.Advertising -> getString(R.string.status_ready, state.name)
            is ReceiverState.Failed -> state.reason
            ReceiverState.Stopped -> getString(R.string.status_starting)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_receiver"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }
    }
}
