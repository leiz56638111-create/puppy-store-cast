// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct

import android.app.*
import android.content.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.airtv.receiver.airplay.*
import com.airtv.receiver.media.VideoPipeline
import com.airtv.receiver.media.AudioPipeline
import android.view.Surface
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class DirectService: Service(), AirPlayCallbacks {
    inner class LocalBinder: Binder() { val service get()=this@DirectService }
    private val binder=LocalBinder()
    private val main=Handler(Looper.getMainLooper())
    lateinit var player: ExoPlayer; private set
    var status="正在启动视频直投接收"; private set
    var port=0; private set
    var requests=0; private set
    var incomingPlayRequests=0; private set
    var rejectedPlayRequests=0; private set
    private var serverQueries=0
    private var reverseRequests=0
    private var playlistActions=0
    private var unsupportedRequests=0
    private var mediaConnections=0
    private var audioNegotiation="尚未协商"
    private var mirrorVideoSeen=false
    var hasMedia=false; private set
    var ticket=0L; private set
    var sourceKind="尚未收到视频"; private set
    var frameShown=false; private set
    @Volatile private var alive=false
    private val streamLock=Any()
    private val streamVideo=VideoPipeline()
    private val streamAudio=AudioPipeline()
    @Volatile private var streamPackets=false
    @Volatile private var streamEpoch=0L
    @Volatile private var surfaceSignal=java.util.concurrent.CountDownLatch(1)
    private var streamAudioType:Int?=null
    var streamActive=false; private set
    var streamWidth=0; private set
    var streamHeight=0; private set
    val streamFrames:Long get()=streamVideo.framesRendered
    val streamAudioFrames:Long get()=streamAudio.framesPlayed
    fun setStreamSurface(surface:Surface?){synchronized(streamLock){
        streamVideo.setSurface(surface)
        if(surface?.isValid==true)surfaceSignal.countDown() else surfaceSignal=java.util.concurrent.CountDownLatch(1)
    }}
    private fun stopStream(){synchronized(streamLock){streamEpoch++;streamPackets=false;streamVideo.stop();streamAudio.stop()};streamActive=false;streamWidth=0;streamHeight=0}

    private var multicast: WifiManager.MulticastLock?=null
    private val events=ArrayDeque<String>()
    var observer:(()->Unit)?=null
    val name="小狗便利店投屏·AirPlay测试"
    val address: String get()=runCatching { NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.asSequence() }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLinkLocalAddress }?.hostAddress }.getOrNull()?:"未取得局域网地址"
    val diagnostic: String get()=buildString {
        append("视频直投实验版 0.3.0-video-alpha4\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        append("地址 $address:$port\n有效直投请求 $requests 次\n来源 $sourceKind\n状态 $status\n")
        append("媒体连接 $mediaConnections 次\n地址直投请求到达 $incomingPlayRequests 次／解析拒绝 $rejectedPlayRequests 次\n设备查询 $serverQueries 次／反向通道 $reverseRequests 次／播放列表响应 $playlistActions 次\n")
        append("未处理的协议请求 $unsupportedRequests 次\n")
        append("音频协商 $audioNegotiation\n视频流协商 ${if(mirrorVideoSeen) "已完成" else "尚未完成"}\n")
        append("画面 ${if(streamActive)streamWidth else player.videoSize.width}×${if(streamActive)streamHeight else player.videoSize.height}\n首帧 ${if(frameShown) "已显示" else "未显示"}\n")
        append("视频流输出帧 $streamFrames\n${streamAudio.diagnostics()}\n")
        append("\n"+events.joinToString("\n"))
        append("\n\n记录只保存在本次运行内存，不显示完整视频地址或授权信息。实际 App 兼容性需要逐个测试。")
    }
    private fun event(text:String) { if(!alive)return;events.addLast(text);while(events.size>18)events.removeFirst();observer?.invoke() }
    override fun onCreate() {
        super.onCreate();alive=true
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("direct_video","AirPlay 视频直投测试",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,DirectActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=(if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"direct_video") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("小狗便利店投屏·AirPlay兼容测试").setContentText("保持界面打开，等待 App 内 AirPlay").setContentIntent(open).setOngoing(true).build()
        startForeground(43,notification)
        player=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("PuppyCast-AirPlay-Video-Test/0.3").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000)))
            .setWakeMode(C.WAKE_MODE_NETWORK).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        streamVideo.onDecodedSize={width,height->accept {streamWidth=width;streamHeight=height;observer?.invoke()}}
        player.addListener(object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int) {
                if(hasMedia && !streamActive)status=when(state) {Player.STATE_READY->if(player.playWhenReady)"正在播放直投视频" else "已暂停";Player.STATE_BUFFERING->"正在加载直投视频";Player.STATE_ENDED->"视频已结束，可重新投送";else->status}
                push();observer?.invoke()
            }
            override fun onIsPlayingChanged(playing:Boolean) { if(hasMedia && !streamActive && player.playbackState==Player.STATE_READY)status=if(player.playWhenReady)"正在播放直投视频" else "已暂停";push();observer?.invoke() }
            override fun onRenderedFirstFrame() {if(streamActive)return;frameShown=true;event("视频首帧已显示")}
            override fun onVideoSizeChanged(size:VideoSize) {observer?.invoke()}
            override fun onPlayerError(error:PlaybackException) {if(streamActive)return;hasMedia=false;status="播放失败：${error.errorCodeName}";event(status);push()}
        })
        runCatching {val wifi=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager;multicast=wifi.createMulticastLock("PuppyCast.direct").apply {setReferenceCounted(false);acquire()}}
        NativeAirPlay.nativeConfigureVideo(true)
        NativeAirPlay.nativeConfigureVideoStream(true)
        val seed=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)+":direct-video-compatible"
        port=NativeAirPlay.nativeStart(name,DeviceIdentity.macAddressFrom(seed),File(filesDir,"airplay_video_compat_pairing.key").absolutePath,1920,1080,30,NativeAirPlay.LOG_WARNING,this)
        status=if(port>0)"等待视频 App 内 AirPlay" else "接收服务启动失败（$port）"
        event(status);main.post(tick)
    }
    private val tick=object:Runnable {override fun run(){
        if(!alive)return
        if(streamActive && streamFrames>0 && !frameShown){frameShown=true;status="正在接收 AirPlay 视频流（兼容模式）";event("兼容视频流首帧已输出")}
        push();observer?.invoke();main.postDelayed(this,250)
    }}
    private fun push() {
        if(!alive || ticket==0L || streamActive)return
        val duration=player.duration.takeIf {it!=C.TIME_UNSET && it>0}?:0L
        val ready=hasMedia && player.playbackState in intArrayOf(Player.STATE_READY,Player.STATE_ENDED) && player.playerError==null
        NativeAirPlay.nativeUpdateVideoState(ticket,player.currentPosition.coerceAtLeast(0)/1000.0,duration/1000.0,
            player.bufferedPosition.coerceAtLeast(0)/1000.0,if(player.isPlaying)player.playbackParameters.speed else 0f,ready,
            player.playbackState==Player.STATE_BUFFERING,player.isCurrentMediaItemSeekable)
    }
    private fun accept(command:()->Unit) {main.post {if(alive)command()}}
    override fun onRemoteVideoPlay(location:String,startSeconds:Float,ticket:Long) = accept {
        if(ticket<this.ticket)return@accept
        if(!DirectRequest.accepted(location)) {status="暂不支持这个视频地址类型";event(status);return@accept}
        stopStream();hasMedia=false;player.stop();player.clearMediaItems();this.ticket=ticket;requests++;frameShown=false
        sourceKind=if(location.startsWith("http://localhost:") || location.startsWith("http://127.0.0.1:"))"发送端协商的 HLS 播放列表" else "HTTP(S) 视频直投"
        hasMedia=true;status="已收到视频，正在加载";event("收到第 $requests 次播放请求：$sourceKind")
        player.setPlaybackSpeed(1f);player.setMediaItem(MediaItem.fromUri(location),DirectRequest.startMillis(startSeconds));player.prepare();player.playWhenReady=true;push()
    }
    override fun onRemoteVideoSeek(seconds:Float,ticket:Long) = accept {if(ticket==this.ticket && hasMedia && !streamActive && seconds.isFinite() && seconds>=0 && player.isCurrentMediaItemSeekable) {player.seekTo(DirectRequest.startMillis(seconds));event("收到进度跳转");push()}}
    override fun onRemoteVideoRate(rate:Float,ticket:Long) = accept {if(ticket==this.ticket && hasMedia && !streamActive && rate.isFinite() && rate in 0f..2f) {if(rate==0f)player.pause() else {player.setPlaybackSpeed(rate);player.play()};event(if(rate==0f)"手机暂停" else "手机继续播放");push()}}
    override fun onRemoteVideoStop(ticket:Long) = accept {if(ticket==this.ticket && !streamActive)stopVideo()}
    fun stopVideo(){stopStream();hasMedia=false;frameShown=false;player.stop();player.clearMediaItems();status="等待下一次视频投送";event("本次视频已结束");push()}
    fun togglePause(){if(hasMedia && !streamActive){if(player.playWhenReady)player.pause() else player.play();push()}}
    override fun onBind(intent:Intent?):IBinder=binder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onDestroy(){alive=false;observer=null;main.removeCallbacksAndMessages(null);NativeAirPlay.nativeStop();stopStream();player.release();multicast?.takeIf{it.isHeld}?.release();stopForeground(true);super.onDestroy()}
    override fun onLog(level:Int,message:String) {
        // Only our fixed event markers enter the UI; never forward raw headers,
        // URLs, session identifiers or native log bodies to the diagnostic page.
        val label=when(message) {
            "[DirectVideo] server-info"->"收到设备能力查询"
            "[DirectVideo] reverse"->"收到反向通道请求"
            "[DirectVideo] action"->"收到播放列表响应"
            "[DirectVideo] play-request"->"收到视频播放请求，正在解析"
            "[DirectVideo] play-rejected"->"视频请求格式或地址暂不支持"
            "[DirectVideo] unhandled"->"收到暂未支持的协议请求"
            else->return
        }
        accept {
            when(message) {
                "[DirectVideo] server-info"->serverQueries++
                "[DirectVideo] reverse"->reverseRequests++
                "[DirectVideo] action"->playlistActions++
                "[DirectVideo] unhandled"->unsupportedRequests++
                "[DirectVideo] play-request"->incomingPlayRequests++
                "[DirectVideo] play-rejected"->{rejectedPlayRequests++;if(!hasMedia)status=label}
            }
            event(label)
        }
    }
    override fun onClientConnect(name:String,model:String,deviceId:String)=true
    override fun onSessionStart()=accept {
        mediaConnections++
        if(!hasMedia){status="已建立媒体连接，等待 App 发送视频";event(status)}
    }
    override fun onSessionEnd(){
        val epoch=streamEpoch
        accept {if(epoch==streamEpoch){if(streamActive)stopVideo() else stopStream()}}
    }
    override fun onVideoCodec(isH265:Boolean):Int {
        val epoch=synchronized(streamLock) {
            if(!alive)return -1
            streamEpoch++;streamPackets=true
            streamVideo.stop();streamVideo.setCodec(isH265)
            streamAudioType?.let{streamAudio.setFormat(it,restart=true)}
            streamEpoch
        }
        accept {
            if(!streamPackets || epoch!=streamEpoch)return@accept
            hasMedia=false;player.stop();player.clearMediaItems()
            streamActive=true;mirrorVideoSeen=true;hasMedia=true;frameShown=false
            sourceKind="AirPlay 视频流（兼容接收，非视频地址直投）"
            status="已协商视频流，正在等待画面";event(status)
        }
        return 0
    }
    override fun onVideoData(data:ByteBuffer,length:Int,presentationTimeUs:Long) {
        // The first keyframe can arrive before Android creates the visible Surface.
        // Wait briefly outside the decoder lock, so the UI can attach it without losing
        // the only keyframe in a short clip. Never block the Android main thread.
        if(alive && streamPackets && Looper.myLooper()!=Looper.getMainLooper())surfaceSignal.await(350,java.util.concurrent.TimeUnit.MILLISECONDS)
        synchronized(streamLock){if(alive && streamPackets)streamVideo.submit(data,length,presentationTimeUs)}
    }
    override fun onVideoSize(width:Int,height:Int) {
        synchronized(streamLock){if(alive)streamVideo.setSourceSize(width,height)}
    }
    override fun onVideoFlush(){synchronized(streamLock){if(alive && streamPackets)streamVideo.flush()}}
    override fun onVideoPause() {}
    override fun onVideoResume() {}
    override fun onVideoStop(){val epoch=streamEpoch;accept {if(streamActive && epoch==streamEpoch)stopVideo()}}
    override fun onAudioFormat(contentType:Int,usingScreen:Boolean,isMedia:Boolean) {
        synchronized(streamLock){streamAudioType=contentType;if(alive && streamPackets)streamAudio.setFormat(contentType,restart=true)}
        accept {
            audioNegotiation="ct=$contentType，usingScreen=$usingScreen，isMedia=$isMedia"
            event("音频协商：$audioNegotiation")
            if(!hasMedia){status="已协商音频，等待视频内容";observer?.invoke()}
        }
    }
    override fun onAudioData(data:ByteBuffer,length:Int,presentationTimeUs:Long){
        synchronized(streamLock){if(alive && streamPackets)streamAudio.submit(data,length,presentationTimeUs)}
    }
    override fun onAudioFlush(){synchronized(streamLock){if(alive && streamPackets)streamAudio.flush()}}
    override fun onVolume(decibels:Float){streamAudio.setVolumeDecibels(decibels)}
}
