// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class DirectActivity:Activity() {
    private enum class Page { WAITING, DIRECT, STREAM }
    private var page:Page?=null
    private lateinit var video:PlayerView
    private lateinit var root:FrameLayout
    private lateinit var streamView:SurfaceView
    private lateinit var streamTools:LinearLayout
    private lateinit var detailsButton:Button
    private lateinit var waitingDetails:Button
    private lateinit var waitingExit:Button
    private lateinit var streamDetails:Button
    private lateinit var streamEnd:Button
    private lateinit var waiting:ScrollView
    private lateinit var status:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val hideStreamTools=Runnable{streamTools.visibility=View.GONE}
    private var service:DirectService?=null
    private var bound=false
    private var dialog:AlertDialog?=null
    private val moves=setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)
    private val selects=setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_BUTTON_A)
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
            service=(binder as DirectService.LocalBinder).service
            video.player=service!!.player;service!!.observer={render()}
            if(streamView.holder.surface.isValid)service!!.setStreamSurface(streamView.holder.surface)
            render()
        }
        override fun onServiceDisconnected(name:ComponentName?){service=null;status.text="接收服务已停止";waiting.visibility=View.VISIBLE;video.visibility=View.GONE;waitingDetails.requestFocus()}
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float)=TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(239,246,243));setPadding(0,12,0,12)}
    private fun decorate(button:Button){
        fun shape(color:Int,stroke:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(6).toFloat();setStroke(dp(2),stroke)}
        button.id=View.generateViewId();button.isFocusable=true;button.isFocusableInTouchMode=true
        button.minHeight=dp(52);button.setPadding(dp(18),dp(8),dp(18),dp(8))
        button.backgroundTintList=null
        button.background=StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_focused),shape(Color.rgb(140,232,195),Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed),shape(Color.rgb(140,232,195),Color.WHITE))
            addState(intArrayOf(),shape(Color.rgb(63,76,85),Color.rgb(91,111,121)))
        }
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),intArrayOf(Color.BLACK,Color.BLACK,Color.WHITE)))
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply{decorate(this);text=label;setOnClickListener{action()}}
    private fun link(buttons:List<Button>){buttons.forEachIndexed {i,b->val other=buttons[(i+1)%buttons.size].id;b.nextFocusUpId=other;b.nextFocusDownId=other;b.nextFocusLeftId=other;b.nextFocusRightId=other}}
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
        streamView.setOnClickListener{showStreamTools(false)};streamView.isFocusable=false;streamView.isFocusableInTouchMode=false
        video=PlayerView(this).apply{id=View.generateViewId();controllerShowTimeoutMs=2000;visibility=View.GONE};root.addView(video,FrameLayout.LayoutParams(-1,-1))
        fun prepareFocus(view:View){if(view.isFocusable)view.isFocusableInTouchMode=true;if(view is ViewGroup)for(i in 0 until view.childCount)prepareFocus(view.getChildAt(i))}
        prepareFocus(video)
        waiting=ScrollView(this).apply{isFocusable=false;descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS}
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,24,40,24)}
        waiting.addView(column);root.addView(waiting,FrameLayout.LayoutParams(-1,-1))
        column.addView(text("小狗便利店投屏 · AirPlay 兼容测试",28f))
        column.addView(text("在视频 App 内点 AirPlay，选择“小狗便利店投屏·AirPlay测试”",20f))
        column.addView(text("这是独立实验版，请保持界面打开。测试前先退出其他 AirPlay 接收软件。",15f))
        status=text("正在启动…",20f);status.setTextColor(Color.rgb(140,232,195));column.addView(status)
        column.addView(text("优先接收视频地址，也支持视频流／屏幕镜像；连接详情会显示实际方式。",15f))
        val row=LinearLayout(this)
        waitingDetails=button("连接详情"){details()};waitingExit=button("退出测试"){finish()}
        row.addView(waitingDetails);row.addView(waitingExit);link(listOf(waitingDetails,waitingExit))
        column.addView(HorizontalScrollView(this).apply{isFocusable=false;addView(row)})
        column.addView(text("遥控器方向键选择，确认键打开；绿色高亮表示当前选中。",14f))
        detailsButton=button("连接详情"){details()}.apply{visibility=View.GONE}
        detailsButton.onFocusChangeListener=View.OnFocusChangeListener {_,focused->
            if(page==Page.DIRECT){video.controllerShowTimeoutMs=if(focused)0 else 2000;video.showController()}
        }
        root.addView(detailsButton,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener{visible->detailsButton.visibility=if(visible==View.VISIBLE && page==Page.DIRECT)View.VISIBLE else View.GONE})
        streamTools=LinearLayout(this).apply{visibility=View.GONE;setBackgroundColor(0xBB102020.toInt())}
        streamDetails=button("连接详情"){details()};streamEnd=button("结束接收"){service?.stopVideo()}
        streamTools.addView(streamDetails);streamTools.addView(streamEnd);link(listOf(streamDetails,streamEnd))
        root.addView(streamTools,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        waitingDetails.post{if(page==null || page==Page.WAITING)waitingDetails.requestFocus()}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),33)
    }
    private fun render(){
        val s=service?:return
        val next=when{!s.hasMedia->Page.WAITING;s.streamActive->Page.STREAM;else->Page.DIRECT}
        val changed=page!=next;page=next
        status.text="${s.status}\n本机 ${s.address}  ·  有效直投请求 ${s.requests} 次"
        waiting.visibility=if(next==Page.WAITING)View.VISIBLE else View.GONE
        streamView.visibility=if(next==Page.STREAM)View.VISIBLE else View.INVISIBLE
        video.visibility=if(next==Page.DIRECT)View.VISIBLE else View.GONE
        video.isFocusable=next==Page.DIRECT
        video.descendantFocusability=if(next==Page.DIRECT)ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        detailsButton.visibility=if(next==Page.DIRECT && video.isControllerFullyVisible)View.VISIBLE else View.GONE
        if(next!=Page.STREAM){streamTools.visibility=View.GONE;handler.removeCallbacks(hideStreamTools)}
        else {
            if(changed)showStreamTools(false)
            if(s.streamWidth>0 && s.streamHeight>0 && root.width>0 && root.height>0){
                val scale=minOf(root.width.toDouble()/s.streamWidth,root.height.toDouble()/s.streamHeight)
                val width=(s.streamWidth*scale).toInt();val height=(s.streamHeight*scale).toInt()
                if(streamView.layoutParams.width!=width || streamView.layoutParams.height!=height)streamView.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER)
            }
        }
        if(changed)handler.post{if(page==next && dialog?.isShowing!=true)restoreFocus()}
    }
    private fun restoreFocus(){when(page){
        Page.WAITING->if(!waitingDetails.hasFocus() && !waitingExit.hasFocus())waitingDetails.requestFocus()
        Page.DIRECT->if(!video.hasFocus() && !detailsButton.hasFocus())video.requestFocus()
        Page.STREAM->if(streamTools.visibility==View.VISIBLE && !streamTools.hasFocus())streamDetails.requestFocus()
        else->Unit
    }}
    private fun showStreamTools(remote:Boolean){
        if(service?.streamActive!=true)return
        streamTools.visibility=View.VISIBLE;handler.removeCallbacks(hideStreamTools);handler.postDelayed(hideStreamTools,if(remote)6000 else 2000)
    }
    private fun navigate(buttons:List<Button>,code:Int){
        val current=buttons.indexOfFirst{it.hasFocus()}
        val next=if(current<0)0 else (current+(if(code==KeyEvent.KEYCODE_DPAD_UP || code==KeyEvent.KEYCODE_DPAD_LEFT)-1 else 1)+buttons.size)%buttons.size
        buttons[next].requestFocus()
    }
    private fun details(){
        dialog?.dismiss()
        val next=AlertDialog.Builder(this).setTitle("连接详情（上下滚动，确认关闭）").setMessage(service?.diagnostic?:"服务正在启动").setPositiveButton("关闭",null).create()
        dialog=next
        next.setOnShowListener{if(page==Page.DIRECT){video.controllerShowTimeoutMs=0;video.showController()};decorate(next.getButton(AlertDialog.BUTTON_POSITIVE));next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()}
        next.setOnDismissListener{if(dialog===next)dialog=null;handler.post{if(hasWindowFocus()){
            if(page==Page.DIRECT && !detailsButton.hasFocus()){video.controllerShowTimeoutMs=2000;video.showController()}
            restoreFocus()
        }}}
        next.setOnKeyListener{_,code,event->
            when {
                code in selects->{if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)next.dismiss();true}
                code==KeyEvent.KEYCODE_DPAD_UP || code==KeyEvent.KEYCODE_DPAD_DOWN->{
                    if(event.action==KeyEvent.ACTION_DOWN){
                        var parent=next.findViewById<TextView>(android.R.id.message)?.parent
                        while(parent!=null && parent !is ScrollView)parent=parent.parent
                        (parent as? ScrollView)?.smoothScrollBy(0,dp(if(code==KeyEvent.KEYCODE_DPAD_UP)-120 else 120))
                    };true
                }
                else->false
            }
        }
        next.show()
    }
    override fun onStart(){super.onStart();page=null;val i=Intent(this,DirectService::class.java);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);bound=bindService(i,connection,BIND_AUTO_CREATE)}
    override fun onStop(){dialog?.dismiss();dialog=null;video.player=null;service?.setStreamSurface(null);service?.observer=null;handler.removeCallbacksAndMessages(null);if(bound){unbindService(connection);bound=false};service=null;stopService(Intent(this,DirectService::class.java));super.onStop()}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus && ::waitingDetails.isInitialized && dialog?.isShowing!=true)restoreFocus()}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        val code=event.keyCode
        if(code in moves || code in selects){
            when(page){
                Page.WAITING->{if(event.action==KeyEvent.ACTION_DOWN && (event.repeatCount==0 || code in moves)){
                    if(code in moves)navigate(listOf(waitingDetails,waitingExit),code)
                    else (if(waitingExit.hasFocus())waitingExit else waitingDetails).performClick()
                };return true}
                Page.STREAM->{if(event.action==KeyEvent.ACTION_DOWN && (event.repeatCount==0 || code in moves)){
                    if(streamTools.visibility!=View.VISIBLE){showStreamTools(true);streamDetails.requestFocus()}
                    else {showStreamTools(true);if(code in moves)navigate(listOf(streamDetails,streamEnd),code) else (if(streamEnd.hasFocus())streamEnd else streamDetails).performClick()}
                };return true}
                Page.DIRECT->{
                    if(code==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN){video.showController();detailsButton.visibility=View.VISIBLE;detailsButton.requestFocus();return true}
                    if(code==KeyEvent.KEYCODE_DPAD_DOWN && detailsButton.hasFocus() && event.action==KeyEvent.ACTION_DOWN){
                        video.showController();detailsButton.clearFocus()
                        video.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.apply{isFocusable=true;isFocusableInTouchMode=true;requestFocus()};return true
                    }
                    if(code in selects){
                        val focused=window.decorView.findFocus()
                        if(detailsButton.hasFocus()){if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)details();return true}
                        if(video.isControllerFullyVisible && focused!=null && focused!==video && focused.isClickable){
                            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)focused.performClick()
                            return true
                        }
                    }
                }
                else->Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean {
        if(code==KeyEvent.KEYCODE_BACK){if(service?.hasMedia==true)service?.stopVideo() else finish();return true}
        if(code==KeyEvent.KEYCODE_MENU){details();return true}
        return super.onKeyDown(code,event)
    }
}
