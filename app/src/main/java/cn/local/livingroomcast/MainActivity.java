/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import io.github.jqssun.airplay.ReceiverMode;
import io.github.jqssun.airplay.ui.NightLobby;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final int INK = NightLobby.INK, MINT = NightLobby.MINT, WHITE = NightLobby.WHITE, MUTED = NightLobby.MUTED;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ReceiverService service;
    private boolean bound, video, switchingMirror;
    private PlayerView playerView;
    private LinearLayout waiting, hud;
    private NightLobby waitingPane;
    private DefaultTimeBar timeBar;
    private TextView timelineLabel;
    private Button speedButton, liveButton;
    private boolean scrubbing, touchHeld;
    private int scrubSession;
    private long scrubWindowStart;
    private TextView status, network, progress, playbackTitle, playbackStatus, playbackTime, playbackQuality;
    private Button pause, qualityButton;
    private AlertDialog modalDialog;
    private int qualityDialogSession = -1;
    private static final long HUD_TIMEOUT_MS = 2000;
    private boolean hudRequested;
    private int displayedSession = -1;
    private final Runnable hideHud = () -> {
        if(modalDialog!=null || scrubbing || touchHeld)return;
        hudRequested=false;
        if(service!=null && service.player().isPlaying() && service.ui().error().isEmpty() && service.player().getPlayerError()==null)hud.setVisibility(View.GONE);
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReceiverService.LocalBinder)binder).get(); playerView.setPlayer(service.player()); service.observe(MainActivity.this::render);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service=null; status.setText("接收服务已停止，请退出后重新打开"); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(INK); setContentView(root);
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setKeepContentOnPlayerReset(false); playerView.setFocusable(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1,-1));
        playerView.setOnClickListener(v -> showHud());

        waitingPane = new NightLobby(this,false,this::openMirror,this::standby,this::details,()->{},this::confirmExit);
        waiting=waitingPane.content; status=waitingPane.statusView; progress=waitingPane.messageView;
        waitingPane.airplayButton.setContentDescription("AirPlay");
        root.addView(waitingPane,new FrameLayout.LayoutParams(-1,-1));

        hud=column(); hud.setPadding(dp(20),dp(12),dp(20),dp(12)); hud.setBackground(background(0xee192927,16));
        root.addView(hud,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)); hud.setVisibility(View.GONE);
        hud.setOnClickListener(v -> {
            if(service!=null && service.player().isPlaying()) {
                hudRequested=false; handler.removeCallbacks(hideHud); hud.setVisibility(View.GONE);
            } else showHud();
        });
        playbackTitle=text("小狗便利店投屏",18,WHITE); playbackTitle.setMaxLines(1); hud.addView(playbackTitle);
        playbackStatus=text("正在连接视频",14,MINT); playbackStatus.setMaxLines(2); add(hud,playbackStatus,4);
        playbackTime=text("",13,MUTED); add(hud,playbackTime,3);
        playbackQuality=text("当前画质  正在识别…",13,WHITE); add(hud,playbackQuality,3);
        timelineLabel=text("正在读取时长…",13,WHITE); hud.addView(timelineLabel);
        timeBar=(DefaultTimeBar)getLayoutInflater().inflate(R.layout.playback_time_bar,hud,false); timeBar.setContentDescription("播放进度"); timeBar.setFocusable(true);timeBar.setFocusableInTouchMode(true);
        timeBar.setKeyTimeIncrement(10_000); timeBar.setPlayedColor(MINT); timeBar.setScrubberColor(MINT);
        timeBar.setBufferedColor(0xff6e8582); timeBar.setUnplayedColor(0xff3b5058);
        hud.addView(timeBar);
        timeBar.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(TimeBar bar,long position) {
                scrubbing=true; handler.removeCallbacks(hideHud);
                if(service!=null) { var snapshot=service.playback();scrubSession=snapshot.generation();scrubWindowStart=snapshot.windowStart(); }
                timelineLabel.setText("跳转至  " + time(position));
            }
            @Override public void onScrubMove(TimeBar bar,long position) { timelineLabel.setText("跳转至  " + time(position)); }
            @Override public void onScrubStop(TimeBar bar,long position,boolean canceled) {
                scrubbing=false;
                if(!canceled && service!=null && !service.seekPlayback(scrubSession,position,scrubWindowStart))
                    Toast.makeText(MainActivity.this,"片源已变化或不支持跳转，请重新操作",Toast.LENGTH_SHORT).show();
                updateTimeline();showHud();
            }
        });
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        pause=button("暂停 / 继续",v->{if(service!=null)service.togglePause();showHud();}); controls.addView(pause);
        qualityButton=button("清晰度",v->quality()); LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(-2,dp(48));qp.leftMargin=dp(12);controls.addView(qualityButton,qp);
        speedButton=button("倍速 1×",v->speed()); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,dp(48));sp.leftMargin=dp(8);controls.addView(speedButton,sp);
        liveButton=button("回到直播",v->{if(service!=null)service.goLive(service.ui().mediaGeneration());showHud();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.leftMargin=dp(8);controls.addView(liveButton,lp);liveButton.setVisibility(View.GONE);
        Button diagnostics=button("连接详情",v->details()); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(48)); cp.leftMargin=dp(12); controls.addView(diagnostics,cp);
        Button disconnect=button("结束投屏",v->{if(service!=null)service.stopPlayback();}); LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-2,dp(48));dp.leftMargin=dp(12);controls.addView(disconnect,dp);
        hud.addView(scrollControls(controls)); add(hud,text("点击画面 / 方向键唤出操作栏 · 返回键结束播放",11,MUTED),5);
        waitingPane.focusDefault();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
    }
    @Override protected void onStart() {
        super.onStart();
        handler.post(progressTick);
    }
    @Override protected void onResume() {
        super.onResume();
        switchingMirror=false;
        // A rapid round trip can pause/resume without stopping/starting this activity.
        // Rebind here as well, instead of relying on onStart to restore DLNA.
        if(ReceiverMode.isAirPlay(this) && io.github.jqssun.airplay.AirPlaySupport.isSupported(Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS)) { openMirror(); return; }
        ReceiverMode.selectDlna(this);
        if(bound)return;
        stopService(new Intent().setClassName(this,"io.github.jqssun.airplay.service.AirPlayService"));
        Intent intent=new Intent(this,ReceiverService.class);
        try {
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent); else startService(intent);
            bound=bindService(intent,connection,BIND_AUTO_CREATE);
        } catch(RuntimeException e) { status.setText("启动失败，请退出重开"); progress.setText(e.getClass().getSimpleName()); }
    }
    private void render() {
        if(service==null)return;
        if(service.isShuttingDown()) { finish(); return; }
        ReceiverService.Ui ui=service.ui();
        if (modalDialog != null && qualityDialogSession >= 0 && (!ui.video() || qualityDialogSession != ui.mediaGeneration())) modalDialog.dismiss();
        boolean ready=!ui.endpoint().isEmpty();
        waitingPane.state(ready?"● 已就绪 · 等待连接":"正在检查网络…",ui.error().isEmpty()?(ready?"":"请连接同一家庭网络"):ui.error(),ready);
        playbackTitle.setText(ui.title());
        playbackStatus.setText(ui.status() + (ui.error().isEmpty()?"":"\n" + ui.error()));
        playbackTime.setText("本次连接  " + ui.elapsed());
        playbackQuality.setText("当前画质  " + ui.quality() + "    ·    " + ui.qualityMode());
        boolean changed=video!=ui.video(); video=ui.video();
        waitingPane.setVisibility(video?View.GONE:View.VISIBLE);
        boolean newSession=changed || displayedSession!=ui.mediaGeneration();
        if(newSession) { hudRequested=false;scrubbing=false; handler.removeCallbacks(hideHud); }
        displayedSession=ui.mediaGeneration();
        if(video) {
            if(newSession) { hud.setVisibility(View.VISIBLE); pause.requestFocus(); }
            boolean playing=service.player().isPlaying();
            boolean paused=service.player().getPlaybackState()==androidx.media3.common.Player.STATE_READY && !service.player().getPlayWhenReady();
            boolean attention=paused || !ui.error().isEmpty() || service.player().getPlayerError()!=null
                    || service.player().getPlaybackState()==androidx.media3.common.Player.STATE_ENDED;
            if(attention)hud.setVisibility(View.VISIBLE);
            else if(playing && !hudRequested && modalDialog==null && !scrubbing && !touchHeld) {
                // A successful cast should reveal the video immediately, without a status overlay.
                handler.removeCallbacks(hideHud);hud.setVisibility(View.GONE);
            }
        } else { hud.setVisibility(View.GONE); if(changed)waitingPane.focusDefault(); }
        updateTimeline();
    }
    private void showHud() {
        if(!video)return;
        hudRequested=true;hud.setVisibility(View.VISIBLE);handler.removeCallbacks(hideHud);
        if(modalDialog==null && !scrubbing && !touchHeld)handler.postDelayed(hideHud,HUD_TIMEOUT_MS);
    }
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if(video && hud.getVisibility()==View.VISIBLE) updateTimeline();
            handler.postDelayed(this,500);
        }
    };
    private void updateTimeline() {
        if(service==null || timeBar==null)return;
        ReceiverService.Playback state=service.playback();
        speedButton.setText("倍速 " + ReceiverService.speedLabel(state.speed()));
        liveButton.setVisibility(state.canGoLive()?View.VISIBLE:View.GONE);
        if(scrubbing)return;
        timeBar.setDuration(state.duration());
        timeBar.setPosition(Math.min(state.position(),state.duration()));
        timeBar.setBufferedPosition(Math.min(state.buffered(),state.duration()));
        timeBar.setEnabled(state.canSeek());
        if(state.live()) timelineLabel.setText(state.canSeek()?"直播回看  " + time(state.position()) + " / " + time(state.duration()):"直播中 · 当前片源不支持拖动或倍速");
        else if(state.duration()>0)timelineLabel.setText(time(state.position()) + " / " + time(state.duration()) + (state.canSeek()?"":" · 当前片源不可拖动"));
        else timelineLabel.setText(service.player().getPlaybackState()==androidx.media3.common.Player.STATE_READY?"当前片源未提供时长 · 不支持拖动":"正在读取时长…");
    }
    private static String time(long millis) {
        long seconds=Math.max(0,millis)/1000;
        return seconds>=3600?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
            touchHeld=true;handler.removeCallbacks(hideHud);
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL) {
            touchHeld=false;
            if(video && hud.getVisibility()==View.VISIBLE)showHud();
        }
        return handled;
    }
    private void speed() {
        if(service==null || modalDialog!=null)return;
        handler.removeCallbacks(hideHud);
        var state=service.playback();
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("播放速度");
        if(!state.canSpeed())builder.setMessage(state.live()?"当前直播没有可回看范围，保持正常速度。片源提供回看后才能调整。":"当前片源未提供可调整的播放范围，请在有时长的视频开始播放后再试。").setPositiveButton("关闭",null);
        else {
            String[] labels=new String[ReceiverService.PLAYBACK_SPEEDS.length];int checked=0;
            for(int i=0;i<labels.length;i++) {
                float value=ReceiverService.PLAYBACK_SPEEDS[i];
                labels[i]=ReceiverService.speedLabel(value)+(value==1f?" · 正常":"");
                if(value==state.speed())checked=i;
            }
            builder.setSingleChoiceItems(labels,checked,(d,which)->{
                if(service==null || !service.selectSpeed(state.generation(),ReceiverService.PLAYBACK_SPEEDS[which]))
                    Toast.makeText(this,"片源已变化或不支持倍速，请重新操作",Toast.LENGTH_SHORT).show();
                d.dismiss();
            }).setNegativeButton("取消",null);
        }
        AlertDialog dialog=builder.create();modalDialog=dialog;qualityDialogSession=state.generation();
        dialog.setOnDismissListener(d->{
            if(modalDialog!=dialog)return;
            modalDialog=null;qualityDialogSession=-1;
            if(service!=null && video && service.ui().mediaGeneration()==state.generation()) {showHud();speedButton.requestFocus();}
            else render();
        });
        dialog.show();fitDialog(dialog,420,-2);
    }
    private HorizontalScrollView scrollControls(LinearLayout row) {
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        scroll.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));return scroll;
    }
    private void fitDialog(AlertDialog dialog,int width,int height) {
        int availableWidth=getResources().getDisplayMetrics().widthPixels-dp(24);
        int availableHeight=getResources().getDisplayMetrics().heightPixels-dp(24);
        dialog.getWindow().setLayout(Math.min(dp(width),availableWidth),height<0?height:Math.min(dp(height),availableHeight));
    }
    private void quality() {
        if(service==null || modalDialog!=null)return;
        handler.removeCallbacks(hideHud);
        ReceiverService.QualityMenu menu=service.qualityMenu();
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("清晰度");
        if(menu.choices().size()<2) {
            String message=menu.loading()?"正在识别片源，开始播放后再查看清晰度。":
                    menu.choices().isEmpty()?"当前内容没有可切换的视频画质。":
                    "当前画质：" + service.ui().quality() + "\n\n当前片源仅提供一个电视可播放的画质档位，无法在这里切换。可尝试在平板 App 中调整清晰度后重新投屏。";
            builder.setMessage(message).setPositiveButton("关闭",(d,w)->{});
        } else {
            String[] labels=new String[menu.choices().size()+1];labels[0]="自动 · 随网络调整";
            int checked=0;
            for(int i=0;i<menu.choices().size();i++) {
                var choice=menu.choices().get(i);labels[i+1]=choice.label();
                if(choice.key().equals(menu.selectedKey()))checked=i+1;
            }
            builder.setSingleChoiceItems(labels,checked,(d,which)->{
                String key=which==0?"":menu.choices().get(which-1).key();
                if(service==null || !service.selectQuality(menu.generation(),key))
                    Toast.makeText(this,"片源已更新，请重新打开清晰度",Toast.LENGTH_SHORT).show();
                d.dismiss();
            }).setNegativeButton("取消",(d,w)->{});
        }
        AlertDialog dialog=builder.create();modalDialog=dialog;qualityDialogSession=menu.generation();
        dialog.setOnDismissListener(d->{
            if(modalDialog!=dialog)return;
            modalDialog=null;qualityDialogSession=-1;
            if(service!=null && video && service.ui().mediaGeneration()==menu.generation()) {
                showHud();qualityButton.requestFocus();
            } else render();
        });
        dialog.show();fitDialog(dialog,620,-2);
    }
    private void details() {
        handler.removeCallbacks(hideHud);
        String message="等待接收服务启动。";
        if(service!=null) {
            ReceiverService.Ui u=service.ui();
            message="小狗便利店投屏 1.0.1\n电视：" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                    + "\n接收器：" + (u.endpoint().isEmpty()?"尚未就绪":u.endpoint())
                    + "\n当前画质：" + u.quality() + " · " + u.qualityMode()
                    + "\n播放速度：" + ReceiverService.speedLabel(service.playback().speed())
                    + "\n收到地址：" + u.received() + " 次\n本次连接：" + u.elapsed()
                    + "\n\n排查顺序：\n1. 没收到搜索：检查同一网络、访客网络隔离。\n2. 能被搜索但没收到地址：可能存在发送端兼容限制。\n3. 已收到地址但不能播放：看下面错误代码。\n\n" + u.logs()
                    + "\n\n记录只保存在本次运行的内存中；不显示完整视频地址或登录信息。\n本地网页可打开只表示网络可达，实际投屏仍需测试。";
        }
        ScrollView scroll=new ScrollView(this); TextView content=text(message,14,WHITE); content.setPadding(dp(24),dp(12),dp(24),dp(20)); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接详情").setView(scroll).setPositiveButton("关闭",(d,w)->showHud()).create();
        modalDialog=dialog;
        dialog.setOnDismissListener(d->{if(modalDialog==dialog)modalDialog=null;showHud();}); dialog.show();
        fitDialog(dialog,660,390);
    }
    private void openMirror() {
        if(switchingMirror)return;
        if(!io.github.jqssun.airplay.AirPlaySupport.isSupported(Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS)) {
            new AlertDialog.Builder(this).setTitle("AirPlay 暂不可用")
                .setMessage("AirPlay 功能需要 Android 7.0 及以上的 ARM 设备；本机仍可使用视频 App 的 TV／投屏接收。")
                .setPositiveButton("知道了",null).show();return;
        }
        switchingMirror=true;
        ReceiverMode.selectAirPlay(this);
        try {
            // Detach before shutdown: shutdown's observer would otherwise finish this
            // host activity, leaving no DLNA screen to return to.
            if(service!=null) {service.observe(null);playerView.setPlayer(null);service.stopPlayback();}
            if(bound){unbindService(connection);bound=false;}
            service=null;
            stopService(new Intent(this,ReceiverService.class));
            startActivity(new Intent().setClassName(this,"io.github.jqssun.airplay.MainActivity"));
        } catch(RuntimeException e) {
            switchingMirror=false;
            ReceiverMode.selectDlna(this);
            Toast.makeText(this,"无法打开 AirPlay，已返回视频投屏。",Toast.LENGTH_LONG).show();
            Intent intent=new Intent(this,ReceiverService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            bound=bindService(intent,connection,BIND_AUTO_CREATE);
        }
    }
    private void exitReceiver() {
        if(service!=null)service.shutdown();
        else stopService(new Intent(this,ReceiverService.class));
        finish();
    }
    private void confirmExit() { new AlertDialog.Builder(this).setTitle("退出接收？").setMessage("将停止后台待机，平板无法再发现本接收器。再次打开即可恢复。").setPositiveButton("退出",(d,w)->exitReceiver()).setNegativeButton("继续接收",null).show(); }
    private void standby() {
        boolean allowed=Build.VERSION.SDK_INT<29 || Settings.canDrawOverlays(this);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("后台待机")
                .setMessage(allowed?"回到桌面后仍可接收投屏，新投屏会尝试自动打开播放界面。电视关机、重启或系统强行停止后，需要重新打开本应用。":
                    "回到桌面后仍可被平板发现。允许“显示在其他应用上层”后，新投屏可自动打开播放界面；不允许时，收到视频后需手动打开本应用播放。此权限不用于显示悬浮窗。")
                .setNegativeButton("取消",null);
        if(allowed)dialog.setPositiveButton("进入后台",(d,w)->moveTaskToBack(true));
        else dialog.setPositiveButton("去授权",(d,w)->{
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
                catch(ActivityNotFoundException unavailable) { Toast.makeText(this,"本机未提供此授权入口，可在系统应用设置中查找；也可手动打开本应用播放。",Toast.LENGTH_LONG).show(); }
            }
        }).setNeutralButton("直接后台等待",(d,w)->moveTaskToBack(true));
        dialog.show();
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_BACK){if(service!=null && service.ui().video())service.stopPlayback();else standby();return true;}
        if(video && (code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code==KeyEvent.KEYCODE_MEDIA_PAUSE || code==KeyEvent.KEYCODE_MEDIA_PLAY)) {
            if(service!=null)service.togglePause();showHud();return true;
        }
        if(video && (code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_DPAD_UP || code==KeyEvent.KEYCODE_DPAD_DOWN || code==KeyEvent.KEYCODE_DPAD_LEFT || code==KeyEvent.KEYCODE_DPAD_RIGHT)) {
            boolean hidden=hud.getVisibility()!=View.VISIBLE;showHud();if(hidden){pause.requestFocus();return true;}
        }
        return super.onKeyDown(code,event);
    }
    @Override protected void onStop() {
        if(modalDialog!=null)modalDialog.dismiss();
        handler.removeCallbacksAndMessages(null);touchHeld=false;scrubbing=false;
        playerView.setPlayer(null); if(service!=null)service.observe(null);
        if(bound){unbindService(connection);bound=false;}
        service=null;
        super.onStop();
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);return v;}
    private Button button(String label,View.OnClickListener click){return NightLobby.button(this,label,false,click);}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
