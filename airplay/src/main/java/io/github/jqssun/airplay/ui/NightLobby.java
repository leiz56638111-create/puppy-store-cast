// SPDX-License-Identifier: GPL-3.0-only
package io.github.jqssun.airplay.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.graphics.Rect;
import android.widget.*;

/** Shared native, remote-focusable night lobby for DLNA and AirPlay. */
public final class NightLobby extends ScrollView {
    public static final int INK=0xff101c1b, PANEL=0xff192927, WHITE=0xffedf4ef, MUTED=0xffa6bcb3, MINT=0xffa5e3c3, LINE=0xff354740;
    public final LinearLayout content;
    public final Button dlnaButton, airplayButton, standbyButton, detailsButton, exitButton, restartButton;
    public final TextView statusView, messageView;
    private final TextView protocolView;

    public NightLobby(Context context, boolean airplay, Runnable switchMode, Runnable standby, Runnable details, Runnable restart, Runnable exit) {
        super(context);
        setFillViewport(true); setBackgroundColor(INK); setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS); setVerticalScrollBarEnabled(false);
        boolean wide=getResources().getConfiguration().screenWidthDp>=700;
        int side=wide?36:24;
        content=column(context); content.setPadding(dp(context,side),dp(context,22),dp(context,side),dp(context,20));
        addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout header=row(context); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=label(context,"汪",16,INK); mark.setGravity(Gravity.CENTER); mark.setTypeface(Typeface.DEFAULT,Typeface.BOLD); mark.setBackground(shape(context,MINT,10,0));
        header.addView(mark,new LinearLayout.LayoutParams(dp(context,34),dp(context,34)));
        TextView brand=label(context,"小狗便利店投屏",wide?17:16,WHITE); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1); bp.leftMargin=dp(context,10); header.addView(brand,bp);
        statusView=label(context,"正在启动…",12,MUTED); header.addView(statusView); content.addView(header);

        LinearLayout modes=row(context);
        dlnaButton=button(context,"视频 App\nTV / 投屏 · DLNA",!airplay,v->{if(airplay)switchMode.run();});
        airplayButton=button(context,"iPhone / iPad\nAirPlay",airplay,v->{if(!airplay)switchMode.run();});
        for(Button b:new Button[]{dlnaButton,airplayButton}) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(wide?dp(context,174):0,dp(context,60),wide?0:1); if(b==airplayButton)p.leftMargin=dp(context,10); modes.addView(b,p); }
        add(content,modes,16);

        LinearLayout main=wide?row(context):column(context); main.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hero=column(context); hero.setGravity(Gravity.CENTER_VERTICAL);
        protocolView=label(context,airplay?"AirPlay 接收已开启":"DLNA 接收已开启",12,MINT); hero.addView(protocolView);
        TextView headline=label(context,"等待你的投屏",wide?32:28,WHITE); headline.setTypeface(Typeface.DEFAULT,Typeface.NORMAL); add(hero,headline,12);
        add(hero,label(context,"在设备列表中选择",12,MUTED),22);
        TextView name=label(context,"小狗便利店投屏",wide?23:22,WHITE); add(hero,name,5);
        messageView=label(context,"",13,MINT); messageView.setVisibility(GONE); add(hero,messageView,12);
        LinearLayout guide=column(context); guide.setPadding(dp(context,22),dp(context,20),dp(context,22),dp(context,20)); guide.setBackground(shape(context,PANEL,16,0));
        guide.addView(label(context,airplay?"从 iPhone / iPad 开始":"从视频 App 开始",15,WHITE));
        step(guide,"01","连接同一个 Wi-Fi", "手机、平板和接收设备在同一网络");
        step(guide,"02",airplay?"在相册或视频 App 中点隔空播放":"在视频 App 中点 TV / 投屏",null);
        step(guide,"03",airplay?"选择本设备，也可用控制中心的屏幕镜像":"选择上方设备名，开始播放",null);
        if(wide) { main.addView(hero,new LinearLayout.LayoutParams(0,-2,1.1f)); LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(0,-2,1); gp.leftMargin=dp(context,28); main.addView(guide,gp); }
        else { main.addView(hero); add(main,guide,22); }
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,0,1); mp.topMargin=dp(context,24); mp.bottomMargin=dp(context,22); main.setMinimumHeight(dp(context,wide?210:320)); content.addView(main,mp);
        View line=new View(context); line.setBackgroundColor(LINE); content.addView(line,new LinearLayout.LayoutParams(-1,dp(context,1)));

        LinearLayout tools=row(context);
        standbyButton=button(context,"后台待机",false,v->standby.run());
        detailsButton=button(context,"连接详情",false,v->details.run());
        restartButton=button(context,"重新连接",false,v->restart.run()); restartButton.setVisibility(airplay?VISIBLE:GONE);
        exitButton=button(context,"退出接收",false,v->exit.run());
        for(Button b:new Button[]{standbyButton,detailsButton,restartButton,exitButton}) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(context,48)); if(b!=standbyButton)p.leftMargin=dp(context,8); tools.addView(b,p); }
        HorizontalScrollView scroll=new HorizontalScrollView(context); scroll.setFocusable(false); scroll.setHorizontalScrollBarEnabled(false); scroll.addView(tools); add(content,scroll,14);
        add(content,label(context,airplay?"当前接收 AirPlay · Home 后继续待机；切换左上方可返回 DLNA":"当前接收 DLNA · Home 后继续待机",12,MUTED),10);
        add(content,label(context,"免费开源 · 1.0.1",11,MUTED),5);
        // Keep D-pad navigation spatial; ScrollView automatically reveals off-screen targets.
        dlnaButton.setNextFocusRightId(airplayButton.getId()); airplayButton.setNextFocusLeftId(dlnaButton.getId());
        dlnaButton.setNextFocusDownId(standbyButton.getId()); airplayButton.setNextFocusDownId(detailsButton.getId());
        Button[] actions=airplay?new Button[]{standbyButton,detailsButton,restartButton,exitButton}:new Button[]{standbyButton,detailsButton,exitButton};
        for(int i=0;i<actions.length;i++) { actions[i].setNextFocusUpId(i==0?dlnaButton.getId():airplayButton.getId()); actions[i].setNextFocusLeftId(actions[Math.max(0,i-1)].getId()); actions[i].setNextFocusRightId(actions[Math.min(actions.length-1,i+1)].getId()); }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // ScrollView normally moves only half a screen when a focus target is far
        // below the viewport. Follow the explicit TV navigation map in one press.
        View focused=findFocus(); int next=View.NO_ID;
        if(event.getAction()==KeyEvent.ACTION_DOWN && focused instanceof Button) {
            switch(event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: next=focused.getNextFocusUpId(); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: next=focused.getNextFocusDownId(); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: next=focused.getNextFocusLeftId(); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: next=focused.getNextFocusRightId(); break;
                default: break;
            }
            View target=next==View.NO_ID?null:findViewById(next);
            if(target!=null && target.isShown() && target.requestFocus()) {
                target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    public void focusDefault() {
        if(getResources().getConfiguration().screenWidthDp<700) {
            (airplayButton.isSelected()?airplayButton:dlnaButton).requestFocus();
        } else detailsButton.requestFocus();
    }
    public void state(String status, String message, boolean ready) {
        statusView.setText(status); statusView.setTextColor(ready?MINT:MUTED);
        protocolView.setText(ready?(airplayButton.isSelected()?"AirPlay 接收已开启":"DLNA 接收已开启"):"正在准备接收");
        messageView.setText(message); messageView.setVisibility(message.isEmpty()?GONE:VISIBLE);
    }
    private void step(LinearLayout guide,String number,String title,String detail) {
        Context c=getContext(); LinearLayout step=row(c); TextView n=label(c,number,12,MINT); step.addView(n,new LinearLayout.LayoutParams(dp(c,30),-2));
        LinearLayout copy=column(c); copy.addView(label(c,title,14,WHITE)); if(detail!=null)add(copy,label(c,detail,12,MUTED),3); step.addView(copy,new LinearLayout.LayoutParams(0,-2,1)); add(guide,step,16);
    }
    public static Button button(Context c,String title,boolean selected,View.OnClickListener listener) {
        Button b=new Button(c); b.setId(View.generateViewId()); b.setText(title); b.setAllCaps(false); b.setTextSize(14); b.setGravity(Gravity.CENTER); b.setPadding(dp(c,16),dp(c,4),dp(c,16),dp(c,4));
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setMinWidth(0); b.setMinimumWidth(0); b.setFocusable(true); b.setFocusableInTouchMode(true); b.setSelected(selected); b.setBackgroundTintList(null);
        int[][] states={new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_selected},new int[]{}};
        StateListDrawable background=new StateListDrawable(); background.addState(states[0],shape(c,MINT,12,WHITE)); background.addState(states[1],shape(c,selected?MINT:PANEL,12,MINT)); background.addState(states[2],shape(c,MINT,12,0)); background.addState(states[3],shape(c,PANEL,12,LINE)); b.setBackground(background);
        b.setTextColor(new ColorStateList(states,new int[]{INK,selected?INK:WHITE,INK,WHITE})); b.setOnClickListener(listener);
        // Acquire focus on DOWN so Button's UP handler still performs the first tap.
        // Keeping focusableInTouchMode also supports D-pad immediately after touch.
        b.setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)b.requestFocus();return false;});
        return b;
    }
    public static GradientDrawable shape(Context c,int color,int radius,int stroke) { GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,radius));if(stroke!=0)g.setStroke(dp(c,2),stroke);return g; }
    public static TextView label(Context c,String text,int size,int color) { TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(c,2),1);return t; }
    private static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private static void add(LinearLayout p,View child,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(p.getContext(),top);p.addView(child,lp);}
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
}
