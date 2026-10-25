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
    private lateinit var waiting:ScrollView
    private lateinit var status:TextView
    private var service:DirectService?=null
    private var bound=false
    private var dialog:AlertDialog?=null
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){service=(binder as DirectService.LocalBinder).service;video.player=service!!.player;service!!.observer={render()};render()}
        override fun onServiceDisconnected(name:ComponentName?){service=null;status.text="接收服务已停止";waiting.visibility=View.VISIBLE}
    }
    private fun text(value:String,size:Float):TextView=TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(239,246,243));setPadding(0,12,0,12)}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root=FrameLayout(this).apply{setBackgroundColor(Color.rgb(16,27,32))};setContentView(root)
        video=PlayerView(this).apply{controllerShowTimeoutMs=2000};root.addView(video,FrameLayout.LayoutParams(-1,-1))
        waiting=ScrollView(this);val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,24,40,24)};waiting.addView(column);root.addView(waiting,FrameLayout.LayoutParams(-1,-1))
        column.addView(text("小狗便利店投屏 · 视频直投测试",28f))
        column.addView(text("在视频 App 内点 AirPlay，选择“小狗便利店投屏·直投测试”",20f))
        column.addView(text("这是独立实验版，请保持界面打开。测试前先退出其他 AirPlay 接收软件。",15f))
        status=text("正在启动…",20f);status.setTextColor(Color.rgb(140,232,195));column.addView(status)
        column.addView(text("不同 App 的直投方式与授权限制不同，当前结果需要逐个记录。",15f))
        val row=LinearLayout(this)
        fun button(label:String,action:()->Unit){row.addView(Button(this).apply{text=label;minHeight=72;setOnClickListener{action()}})}
        button("连接详情"){details()};button("退出测试"){finish()};column.addView(HorizontalScrollView(this).apply{addView(row)})
        val detailsButton=Button(this).apply{text="连接详情";visibility=View.GONE;setOnClickListener{details()}}
        root.addView(detailsButton,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible -> detailsButton.visibility=if(visible==View.VISIBLE && service?.hasMedia==true)View.VISIBLE else View.GONE })
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),33)
    }
    private fun render(){val s=service?:return;status.text="${s.status}\n本机 ${s.address}  ·  已收到 ${s.requests} 次视频请求";waiting.visibility=if(s.hasMedia)View.GONE else View.VISIBLE}
    private fun details(){dialog?.dismiss();dialog=AlertDialog.Builder(this).setTitle("视频直投连接详情").setMessage(service?.diagnostic?:"服务正在启动").setPositiveButton("关闭",null).show()}
    override fun onStart(){super.onStart();val i=Intent(this,DirectService::class.java);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);bound=bindService(i,connection,BIND_AUTO_CREATE)}
    override fun onStop(){dialog?.dismiss();dialog=null;video.player=null;service?.observer=null;if(bound){unbindService(connection);bound=false};service=null;stopService(Intent(this,DirectService::class.java));super.onStop()}
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean {
        if(code==KeyEvent.KEYCODE_BACK){if(service?.hasMedia==true)service?.stopVideo() else finish();return true}
        if(code==KeyEvent.KEYCODE_MENU){details();return true}
        return super.onKeyDown(code,event)
    }
}
