// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class DirectActivity:Activity() {
    private lateinit var video:PlayerView
    private lateinit var root:FrameLayout
    private lateinit var streamView:SurfaceView
    private lateinit var streamTools:LinearLayout
    private lateinit var detailsButton:Button
    private val handler=Handler(Looper.getMainLooper())
    private val hideStreamTools=Runnable{streamTools.visibility=View.GONE}
    private lateinit var waiting:ScrollView
    private lateinit var status:TextView
    private var service:DirectService?=null
    private var bound=false
    private var dialog:AlertDialog?=null
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){service=(binder as DirectService.LocalBinder).service;video.player=service!!.player;service!!.observer={render()};if(streamView.holder.surface.isValid)service!!.setStreamSurface(streamView.holder.surface);render()}
        override fun onServiceDisconnected(name:ComponentName?){service=null;status.text="接收服务已停止";waiting.visibility=View.VISIBLE}
    }
    private fun text(value:String,size:Float):TextView=TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(239,246,243));setPadding(0,12,0,12)}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(16,27,32))};setContentView(root)
        streamView=SurfaceView(this).apply{visibility=View.INVISIBLE}
        root.addView(streamView,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        streamView.holder.addCallback(object:SurfaceHolder.Callback {
            override fun surfaceCreated(holder:SurfaceHolder){service?.setStreamSurface(holder.surface)}
            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}
            override fun surfaceDestroyed(holder:SurfaceHolder){service?.setStreamSurface(null)}
        })
        streamView.setOnClickListener{showStreamTools()}
        video=PlayerView(this).apply{controllerShowTimeoutMs=2000};root.addView(video,FrameLayout.LayoutParams(-1,-1))
        waiting=ScrollView(this);val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,24,40,24)};waiting.addView(column);root.addView(waiting,FrameLayout.LayoutParams(-1,-1))
        column.addView(text("小狗便利店投屏 · AirPlay 兼容测试",28f))
        column.addView(text("在视频 App 内点 AirPlay，选择“小狗便利店投屏·AirPlay测试”",20f))
        column.addView(text("这是独立实验版，请保持界面打开。测试前先退出其他 AirPlay 接收软件。",15f))
        status=text("正在启动…",20f);status.setTextColor(Color.rgb(140,232,195));column.addView(status)
        column.addView(text("优先接收视频地址，也支持视频流／屏幕镜像；连接详情会显示实际方式。",15f))
        val row=LinearLayout(this)
        fun button(label:String,action:()->Unit){row.addView(Button(this).apply{text=label;minHeight=72;setOnClickListener{action()}})}
        button("连接详情"){details()};button("退出测试"){finish()};column.addView(HorizontalScrollView(this).apply{addView(row)})
        detailsButton=Button(this).apply{text="连接详情";visibility=View.GONE;setOnClickListener{details()}}
        root.addView(detailsButton,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible -> detailsButton.visibility=if(visible==View.VISIBLE && service?.hasMedia==true && service?.streamActive!=true)View.VISIBLE else View.GONE })
        streamTools=LinearLayout(this).apply{visibility=View.GONE;setBackgroundColor(0xBB102020.toInt())}
        streamTools.addView(Button(this).apply{text="连接详情";setOnClickListener{details()}})
        streamTools.addView(Button(this).apply{text="结束接收";setOnClickListener{service?.stopVideo()}})
        root.addView(streamTools,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),33)
    }
    private fun render(){
        val s=service?:return
        status.text="${s.status}\n本机 ${s.address}  ·  有效直投请求 ${s.requests} 次"
        waiting.visibility=if(s.hasMedia)View.GONE else View.VISIBLE
        val entered=s.streamActive && streamView.visibility!=View.VISIBLE
        streamView.visibility=if(s.streamActive)View.VISIBLE else View.INVISIBLE
        video.visibility=if(s.streamActive)View.GONE else View.VISIBLE
        detailsButton.visibility=if(!s.streamActive && s.hasMedia && video.isControllerFullyVisible)View.VISIBLE else View.GONE
        if(!s.streamActive){streamTools.visibility=View.GONE;handler.removeCallbacks(hideStreamTools)}
        else {
            if(entered)showStreamTools()
            if(s.streamWidth>0 && s.streamHeight>0 && root.width>0 && root.height>0){
                val scale=minOf(root.width.toDouble()/s.streamWidth,root.height.toDouble()/s.streamHeight)
                val width=(s.streamWidth*scale).toInt();val height=(s.streamHeight*scale).toInt()
                if(streamView.layoutParams.width!=width || streamView.layoutParams.height!=height)streamView.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER)
            }
        }
    }
    private fun showStreamTools(){if(service?.streamActive!=true)return;streamTools.visibility=View.VISIBLE;handler.removeCallbacks(hideStreamTools);handler.postDelayed(hideStreamTools,2000)}
    private fun details(){dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle("视频直投连接详情").setMessage(service?.diagnostic?:"服务正在启动").setPositiveButton("关闭",null).show()}
    override fun onStart(){super.onStart();val i=Intent(this,DirectService::class.java);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);bound=bindService(i,connection,BIND_AUTO_CREATE)}
    override fun onStop(){dialog?.dismiss();dialog=null;video.player=null;service?.setStreamSurface(null);service?.observer=null;handler.removeCallbacksAndMessages(null);if(bound){unbindService(connection);bound=false};service=null;stopService(Intent(this,DirectService::class.java));super.onStop()}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(service?.streamActive==true && event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER){
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0){
                if(streamTools.visibility!=View.VISIBLE){showStreamTools();streamTools.getChildAt(0).requestFocus()}
                else (streamTools.focusedChild?:streamTools.getChildAt(0)).performClick()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean {
        if(code==KeyEvent.KEYCODE_BACK){if(service?.hasMedia==true)service?.stopVideo() else finish();return true}
        if(code==KeyEvent.KEYCODE_MENU){details();return true}
        if(service?.streamActive==true && streamTools.visibility!=View.VISIBLE && code in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){showStreamTools();streamTools.getChildAt(0).requestFocus();return true}
        if(service?.streamActive==true && streamTools.visibility==View.VISIBLE){handler.removeCallbacks(hideStreamTools);handler.postDelayed(hideStreamTools,2000)}
        return super.onKeyDown(code,event)
    }
}
