/* Copyright (C) 2026 小狗便利店投屏 contributors; SPDX-License-Identifier: GPL-3.0-only */
package cn.local.livingroomcast;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.ui.DefaultTimeBar;
import cn.local.livingroomcast.core.Dlna;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Real media, native pointer/key events, and SOAP state: not a mock player. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlaybackInstrumentation extends Instrumentation {
    private ReceiverService service;
    private Activity activity;
    private long lastScrubMs;
    private final List<String> checks=new ArrayList<>();
    private <T> T ui(Supplier<T> task) {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{result.set(task.get());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new AssertionError(error.get());return result.get();
    }
    private void check(String message,boolean ok){if(!ok)throw new AssertionError(message);checks.add("PASS "+message);}
    private void await(String message,BooleanSupplier test)throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean()){check(message,true);return;}Thread.sleep(100);}
        throw new AssertionError(message+"; state="+ui(()->service.playback().toString())+"; log="+ui(()->service.ui().logs()));
    }
    private View find(String text) {
        ArrayList<View> views=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(views,text,View.FIND_VIEWS_WITH_TEXT);
        return views.stream().filter(View::isShown).findFirst().orElse(null);
    }
    private DefaultTimeBar bar(View view) {
        if(view instanceof DefaultTimeBar && view.isShown())return (DefaultTimeBar)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){var b=bar(((ViewGroup)view).getChildAt(i));if(b!=null)return b;}
        return null;
    }
    private boolean visible(){return ui(()->find("暂停 / 继续")!=null);}
    private void pointer(long down,int action,float x,float y) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(e);e.recycle();
    }
    private void tapPoint(float x,float y) {long down=SystemClock.uptimeMillis();pointer(down,0,x,y);pointer(down,1,x,y);}
    private void reveal()throws Exception {
        if(!visible()){int[] size=ui(()->new int[]{activity.getWindow().getDecorView().getWidth(),activity.getWindow().getDecorView().getHeight()});tapPoint(size[0]/2f,size[1]/2f);}
        await("touch reveals playback controls",this::visible);
    }
    private void touchButton(String label)throws Exception {
        int[] point=ui(()->{ArrayList<View> candidates=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(candidates,label,View.FIND_VIEWS_WITH_TEXT);View v=candidates.stream().filter(x->x instanceof android.widget.Button && x.isShown()).findFirst().orElse(null);if(v==null)throw new AssertionError("missing button "+label);int[] xy=new int[2];v.getLocationOnScreen(xy);xy[0]+=v.getWidth()/2;xy[1]+=v.getHeight()/2;return xy;});tapPoint(point[0],point[1]);
    }
    private boolean menuTap(String text) {
        var root=getUiAutomation().getRootInActiveWindow();if(root==null || !menuOpen())return false;
        for(var node:root.findAccessibilityNodeInfosByText(text))if(node.isVisibleToUser() && text.contentEquals(node.getText()==null?"":node.getText())){
            for(int i=0;i<4 && node!=null;i++,node=node.getParent())if(node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        scrollList(root);return false;
    }
    private boolean scrollList(AccessibilityNodeInfo node) {
        if(node.isScrollable() && "android.widget.ListView".contentEquals(node.getClassName()))return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null && scrollList(child))return true;}return false;
    }
    private boolean menuOpen(){var root=getUiAutomation().getRootInActiveWindow();return root!=null && !root.findAccessibilityNodeInfosByText("播放速度").isEmpty();}
    private void source(String path)throws Exception {
        service.action(Dlna.AV,"SetAVTransportURI",Dlna.values("InstanceID","0","CurrentURI","http://10.0.2.2:18099/"+path,"CurrentURIMetaData",""));
        service.action(Dlna.AV,"Play",Dlna.values("InstanceID","0","Speed","1"));
        await("real source plays: "+path,()->ui(()->service.player().isPlaying()));
    }
    private void shot(String name)throws Exception {
        Thread.sleep(350);
        File dir=new File(getTargetContext().getFilesDir(),"playback-tests");dir.mkdirs();
        try(var out=new FileOutputStream(new File(dir,name))){getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    private int[] barGeometry(){return ui(()->{View b=bar(activity.getWindow().getDecorView());int[] xy=new int[4];b.getLocationOnScreen(xy);xy[2]=b.getWidth();xy[3]=b.getHeight();return xy;});}
    private void drag(float fraction,long hold)throws Exception {
        int[] g=barGeometry();long down=SystemClock.uptimeMillis();float y=g[1]+g[3]/2f;
        checks.add("INFO geometry="+Arrays.toString(g));pointer(down,0,g[0]+g[2]*.15f,y);Thread.sleep(20);
        for(int i=1;i<=10;i++){pointer(down,2,g[0]+g[2]*(.15f+(fraction-.15f)*i/10),y);Thread.sleep(20);}
        pointer(down,2,g[0]+g[2]*fraction,y);
        Thread.sleep(hold);pointer(down,2,g[0]+g[2]*fraction,y);Thread.sleep(30);check("pointer starts the native scrub preview",ui(()->find("跳转至")!=null));check("scrubbing keeps controls visible",visible());checks.add("INFO preview="+ui(()->((android.widget.TextView)find("跳转至")).getText().toString())+" paused="+ui(()->!service.player().getPlayWhenReady()));String preview=ui(()->((android.widget.TextView)find("跳转至")).getText().toString()).replace("跳转至", "").trim();
        lastScrubMs=0;for(String part:preview.split(":"))lastScrubMs=lastScrubMs*60+Long.parseLong(part);lastScrubMs*=1000;
        pointer(down,1,g[0]+g[2]*fraction,y);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        CountDownLatch bound=new CountDownLatch(1);Bundle result=new Bundle();
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ReceiverService.LocalBinder)b).get();bound.countDown();}public void onServiceDisconnected(ComponentName n){}};
        try {
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getTargetContext().bindService(new Intent(getTargetContext(),ReceiverService.class),connection,Context.BIND_AUTO_CREATE);
            check("service bound",bound.await(10,TimeUnit.SECONDS));
            check("empty receiver offers no fake playback range",ui(()->!service.playback().canSeek() && !service.playback().canSpeed()));
            source("360/index.m3u8");
            check("VOD has its real duration and seek capability",ui(()->service.playback().duration()>170000 && service.playback().canSeek()));
            check("new source starts at normal speed",ui(()->service.playback().speed()==1f));
            check("successful video remains unobstructed",!visible());
            reveal();check("time bar is enabled on seekable VOD",ui(()->bar(activity.getWindow().getDecorView()).isEnabled()));
            shot("播放控制-进度与倍速.png");
            touchButton("暂停 / 继续");await("touch pauses real playback",()->ui(()->!service.player().getPlayWhenReady()));
            drag(.55f,2500);
            checks.add("INFO after drag="+ui(()->service.playback())+" paused="+ui(()->!service.player().getPlayWhenReady()));
            await("drag seeks to the chosen VOD position",()->ui(()->service.player().getCurrentPosition()>85000 && service.player().getCurrentPosition()<115000));
            check("seeking preserves pause",ui(()->!service.player().getPlayWhenReady()));
            long before=ui(()->service.player().getCurrentPosition());
            ui(()->{check("progress accepts remote focus after touch",bar(activity.getWindow().getDecorView()).requestFocus());return null;});sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);Thread.sleep(1400);
            check("remote right seeks ten seconds",ui(()->service.player().getCurrentPosition())>=before+8000);
            touchButton("倍速");await("touch opens speed menu",this::menuOpen);Thread.sleep(2200);
            check("speed menu survives auto-hide timeout",menuOpen());shot("播放速度菜单.png");
            check("1.5x menu selection accepted",menuTap("1.5×"));
            await("player applies 1.5x",()->ui(()->service.player().getPlaybackParameters().speed==1.5f));
            check("speed selection does not resume paused video",ui(()->!service.player().getPlayWhenReady()));
            touchButton("暂停 / 继续");await("touch resumes video",()->ui(()->service.player().isPlaying()));
            check("pause and resume preserve chosen speed",ui(()->service.playback().speed()==1.5f));
            reveal();touchButton("倍速");await("2x menu selection accepted",()->menuTap("2×"));
            long start=ui(()->service.player().getCurrentPosition());long wall=SystemClock.elapsedRealtime();Thread.sleep(2100);
            long advanced=ui(()->service.player().getCurrentPosition())-start;long elapsed=SystemClock.elapsedRealtime()-wall;
            check("real media clock advances at double speed: "+advanced+"ms / "+elapsed+"ms",advanced>elapsed*1.6 && advanced<elapsed*2.5);
            check("DLNA reports the actual chosen speed",service.action(Dlna.AV,"GetTransportInfo",Dlna.values("InstanceID","0")).get("CurrentSpeed").equals("2"));
            check("DLNA event reports the chosen speed",service.event(Dlna.AV).get("TransportPlaySpeed").equals("2"));
            var old=ui(()->service.playback());
            check("invalid speed is rejected",ui(()->!service.selectSpeed(old.generation(),Float.NaN) && !service.selectSpeed(old.generation(),3f)));
            reveal();touchButton("倍速");source("720/index.m3u8");
            check("new cast dismisses stale speed menu",!menuOpen());
            check("new cast clears old speed",ui(()->service.playback().speed()==1f && service.player().getPlaybackParameters().speed==1f));
            check("old drag cannot seek a new cast",!ui(()->service.seekPlayback(old.generation(),120000,old.windowStart())));
            check("old menu cannot change a new cast",!ui(()->service.selectSpeed(old.generation(),2f)));
            check("new cast immediately hides controls",!visible());
            source("live.m3u8");await("HLS exposes a finite live replay window",()->ui(()->service.playback().live() && service.playback().canSeek()));
            reveal();shot("直播回看控制.png");
            touchButton("暂停 / 继续");await("live replay can pause",()->ui(()->!service.player().getPlayWhenReady()));
            var live=ui(()->service.playback());
            drag(.35f,3500);var after=ui(()->service.playback());
            check("live replay window advances during a drag",after.windowStart()>live.windowStart());
            long expected=lastScrubMs+live.windowStart()-after.windowStart();
            checks.add("INFO live before="+live+" after="+after+" expected="+expected);
            check("live drag compensates for the moving window",Math.abs(after.position()-Math.max(0,expected))<3000);
            check("DVR replay permits speed selection",ui(()->service.selectSpeed(after.generation(),1.5f)));
            touchButton("回到直播");
            await("return to live resets speed",()->ui(()->service.playback().speed()==1f));
            check("return to live preserves pause",ui(()->!service.player().getPlayWhenReady()));
            var current=ui(()->service.playback());
            ui(()->{var timeline=service.player().getCurrentTimeline();long edge=timeline.getWindow(0,new androidx.media3.common.Timeline.Window()).getDefaultPositionMs();service.seekPlayback(current.generation(),Math.max(0,edge-300),current.windowStart());service.selectSpeed(current.generation(),2f);service.togglePause();return null;});
            await("catching the live position restores normal speed",()->ui(()->service.player().isPlaying() && service.playback().speed()==1f));
            source("unseekable.ts");await("unbounded transport stream does not offer seeking or speed",()->ui(()->service.playback().duration()==0 && !service.playback().canSeek() && !service.playback().canSpeed()));
            reveal();check("unknown-length stream disables time bar",ui(()->!bar(activity.getWindow().getDecorView()).isEnabled()));
            touchButton("倍速");await("unbounded source explains speed limit",()->getUiAutomation().getRootInActiveWindow()!=null && (!getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("当前片源未提供可调整的播放范围").isEmpty() || !getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("当前直播没有可回看范围").isEmpty()));sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            var noSeek=ui(()->service.playback());check("unbounded stream rejects stale local seek",!ui(()->service.seekPlayback(noSeek.generation(),10000,0)));
            try{service.action(Dlna.AV,"Seek",Dlna.values("InstanceID","0","Unit","REL_TIME","Target","00:00:10"));throw new AssertionError("unbounded seek accepted");}catch(Dlna.Fault expectedFault){check("DLNA rejects seek when source cannot seek",true);}
            ui(()->{service.stopPlayback();return null;});check("stop resets speed and disables timeline",ui(()->service.playback().speed()==1f && !service.playback().canSeek()));
            shot("平板等待界面.png");
            result.putString("stream",String.join("\n",checks)+"\nPLAYBACK_ANDROID_OK: "+checks.size()+" checks\n");finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream",String.join("\n",checks)+"\n"+trace);finish(Activity.RESULT_CANCELED,result);}
        finally {try{getTargetContext().unbindService(connection);}catch(Exception ignored){}if(activity!=null)runOnMainSync(()->activity.finish());}
    }
}
