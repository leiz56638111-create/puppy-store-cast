/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import cn.local.livingroomcast.core.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ReceiverService extends Service implements Dlna.Controller {
    public static final int NOTICE_ID = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService networkWork = Executors.newSingleThreadExecutor();
    private final ExecutorService formatWork = Executors.newSingleThreadExecutor();
    private final Binder binder = new LocalBinder();
    private final List<String> history = new ArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private ExoPlayer player;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audio;
    private volatile HttpServer http;
    private SsdpServer ssdp;
    private volatile boolean alive;
    private String address = "", endpoint = "", uuid, currentUri = "", metadata = "", title = "", mime = "";
    private String status = "正在启动接收服务", error = "", transportStatus = "OK";
    private Runnable observer;
    private String selectedQuality = "";
    private float selectedSpeed = 1f;
    public static final float[] PLAYBACK_SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    public record Playback(int generation, long position, long duration, long buffered, long windowStart,
                           boolean live, boolean canSeek, boolean canSpeed, boolean canGoLive, float speed) {}
    private int received, retries;
    private boolean desiredPlay, formatProbed, probing, pendingPresentation, shuttingDown;
    private int mediaGeneration;
    private Future<?> formatJob;
    private long sessionStarted, playingSince;
    private Runnable pendingRetry;
    public final class LocalBinder extends Binder { ReceiverService get() { return ReceiverService.this; } }
    public record Ui(String status, String error, String address, String endpoint, String title, int received, String logs, String elapsed, boolean video, String quality, String qualityMode, int mediaGeneration) {}
    public record QualityMenu(int generation, List<QualityCatalog.Choice> choices, String selectedKey, boolean loading) {}

    @Override public void onCreate() {
        super.onCreate(); alive = true;
        uuid = getSharedPreferences("receiver", MODE_PRIVATE).getString("uuid", "");
        if (uuid.isEmpty()) { uuid = UUID.randomUUID().toString(); getSharedPreferences("receiver", MODE_PRIVATE).edit().putString("uuid", uuid).apply(); }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("receiver", "小狗便利店投屏接收", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        foreground(false);
        audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        var httpFactory = new DefaultHttpDataSource.Factory().setUserAgent(MediaProbe.USER_AGENT)
                .setConnectTimeoutMs(12000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, httpFactory)))
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(12000,45000,1500,3000).build())
                .setWakeMode(C.WAKE_MODE_NETWORK).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING && desiredPlay) status = "正在连接直播";
                if (state == Player.STATE_READY) { transportStatus = "OK"; error = ""; status = player.getPlayWhenReady() ? "正在播放" : "已暂停"; }
                if (state == Player.STATE_ENDED && desiredPlay) {
                    if (player.isCurrentMediaItemLive()) scheduleRetry("直播连接已结束");
                    else { desiredPlay = false; status = "节目已结束，可重新投屏"; }
                }
                changed();
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) { status = "正在播放"; playingSince = SystemClock.elapsedRealtime(); if (sessionStarted == 0) sessionStarted = playingSince; }
                else playingSince = 0;
                changed();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                if (!selectedQuality.isEmpty() && !tracks.isEmpty()) {
                    QualityCatalog.Choice choice = findQuality(selectedQuality);
                    if (choice == null) {
                        resetQuality();
                        log("片源画质已更新，恢复自动选择");
                    } else applyQuality(choice);
                }
                changed();
            }
            @Override public void onTimelineChanged(Timeline timeline, int reason) {
                if (player.isCurrentMediaItemLive() && !player.isCurrentMediaItemSeekable() && selectedSpeed != 1f) setSpeedInternal(1f);
                changed();
            }
            @Override public void onAvailableCommandsChanged(Player.Commands commands) { changed(); }
            @Override public void onPlaybackParametersChanged(PlaybackParameters parameters) { changed(); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) { changed(); }
            @Override public void onVideoSizeChanged(VideoSize videoSize) { changed(); }
            @Override public void onPlayerError(PlaybackException e) {
                playingSince = 0;
                int response = responseCode(e);
                log("播放错误：" + e.getErrorCodeName() + (response == 0 ? "" : "，HTTP " + response));
                if (response == 401 || response == 403 || response == 404 || response == 410) {
                    fail("直播地址已失效或服务器拒绝访问，请在 Z 视介重新投屏。"); return;
                }
                if (!formatProbed && e.errorCode >= 3001 && e.errorCode <= 3004) {
                    inspectFailedMedia(); return;
                }
                if (e.errorCode >= 2000 && e.errorCode < 3000) scheduleRetry("直播网络连接中断");
                else if (e.errorCode >= 4000 && e.errorCode < 5000) fail("电视无法解码当前视频。请打开连接详情，记录错误代码。");
                else fail("直播内容无法解析。请打开连接详情，记录错误代码。");
            }
        });
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            multicastLock = wifi.createMulticastLock("LivingRoomCast.discovery"); multicastLock.setReferenceCounted(false); multicastLock.acquire();
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LivingRoomCast.playback"); wifiLock.setReferenceCounted(false); wifiLock.acquire();
        } catch (RuntimeException e) { log("系统未允许 Wi-Fi 保持或组播锁，继续尝试接收"); }
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.postDelayed(ReceiverService.this::refreshNetwork, 500); }
            @Override public void onLost(Network network) { main.post(ReceiverService.this::refreshNetwork); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) { main.post(ReceiverService.this::refreshNetwork); }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
        log("测试版启动：只接收局域网投屏，不设人为时长限制");
        refreshNetwork(); main.postDelayed(tick, 1000);
    }
    private void foreground(boolean playing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "receiver") : new Notification.Builder(this);
        Intent stop = new Intent(this, ReceiverService.class).setAction("cn.local.livingroomcast.STOP");
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder.setContentTitle("小狗便利店投屏").setContentText(pendingPresentation ? "已收到投屏，打开应用开始播放" : playing ? "正在接收视频" : "后台待机 · 等待平板连接")
                .setSmallIcon(R.drawable.app_icon).setContentIntent(pending).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"退出接收",stopPending).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | (playing ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0));
        else startForeground(NOTICE_ID, notification);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (playingSince > 0 && SystemClock.elapsedRealtime() - playingSince > 60000) retries = 0;
            if (selectedSpeed > 1f && player.isPlaying() && player.isCurrentMediaItemLive()) {
                Timeline timeline = player.getCurrentTimeline();
                if (!timeline.isEmpty()) {
                    long edge = timeline.getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window()).getDefaultPositionMs();
                    if (edge != C.TIME_UNSET && edge - player.getCurrentPosition() <= 1500) {
                        setSpeedInternal(1f); log("已接近直播位置，恢复正常速度");
                    }
                }
            }
            if (observer != null) observer.run();
            main.postDelayed(this, 1000);
        }
    };
    private void refreshNetwork() {
        if (!alive) return;
        InetAddress candidate = null;
        List<Network> networks = new ArrayList<>(Arrays.asList(connectivity.getAllNetworks()));
        Network active = connectivity.getActiveNetwork(); if (active != null) { networks.remove(active); networks.add(0, active); }
        for (Network n : networks) {
            NetworkCapabilities cap = connectivity.getNetworkCapabilities(n);
            if (cap == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties links = connectivity.getLinkProperties(n); if (links == null) continue;
            for (LinkAddress link : links.getLinkAddresses()) if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress() && !link.getAddress().isLinkLocalAddress()) { candidate = link.getAddress(); break; }
            if (candidate != null) break;
        }
        String next = candidate == null ? "" : candidate.getHostAddress();
        if (next.equals(address) && (!next.isEmpty() || http != null)) return;
        if (next.isEmpty() && address.isEmpty() && status.equals("请连接家庭 Wi-Fi 或有线网络")) return;
        address = next; endpoint = "";
        int ticket = generation.incrementAndGet(); InetAddress selected = candidate;
        networkWork.execute(() -> {
            closeNetwork(); if (!alive || ticket != generation.get()) return;
            if (selected == null) { main.post(() -> { if(alive && ticket == generation.get()) { status = "请连接家庭 Wi-Fi 或有线网络"; changed(); } }); return; }
            HttpServer newHttp = null; SsdpServer newSsdp = null;
            try {
                try { newHttp = new HttpServer(selected, 8192, uuid, this, this::log); }
                catch (java.io.IOException busy) { newHttp = new HttpServer(selected, 0, uuid, this, this::log); }
                newSsdp = new SsdpServer(selected, uuid, newHttp.base() + "/description.xml", this::log);
                if (!alive || ticket != generation.get()) { newSsdp.close(); newHttp.close(); return; }
                http = newHttp; ssdp = newSsdp; newHttp.start(); newSsdp.start();
                String url = newHttp.base(); main.post(() -> {
                    if (!alive || ticket != generation.get()) return;
                    endpoint = url; if (currentUri.isEmpty()) status = "等待平板投屏";
                    log("接收器已就绪：" + selected.getHostAddress()); changed();
                });
            } catch (Exception e) {
                if (newSsdp != null) newSsdp.close(); if (newHttp != null) newHttp.close();
                main.post(() -> { if(alive && ticket == generation.get()) { address = ""; status = "接收服务启动失败"; error = "请退出重开，并检查电视网络。"; log("启动失败：" + e.getClass().getSimpleName()); changed(); } });
            }
        });
        changed();
    }
    private void closeNetwork() { if (ssdp != null) { ssdp.close(); ssdp = null; } if (http != null) { http.close(); http = null; } }
    private <T> T onMain(Callable<T> call) throws Exception {
        if (!alive) throw new Dlna.Fault(501, "Receiver stopped");
        if (Looper.myLooper() == main.getLooper()) return call.call();
        FutureTask<T> task = new FutureTask<>(call); main.post(task);
        try { return task.get(4, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if(e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw e; }
        catch (TimeoutException e) { task.cancel(false); throw new Dlna.Fault(501, "Receiver busy"); }
    }
    @Override public Map<String,String> action(String service, String name, Map<String,String> args) throws Exception {
        return onMain(() -> dispatch(service, name, args));
    }
    private Map<String,String> dispatch(String service, String name, Map<String,String> args) throws Exception {
        if (args.containsKey("InstanceID") && !"0".equals(args.get("InstanceID"))) throw new Dlna.Fault(718, "Invalid InstanceID");
        if (args.containsKey("Channel") && !"Master".equals(args.get("Channel"))) throw new Dlna.Fault(402, "Invalid channel");
        if (service.equals(Dlna.CM)) return switch (name) {
            case "GetProtocolInfo" -> Dlna.values("Source", "", "Sink", Dlna.PROTOCOLS);
            case "GetCurrentConnectionIDs" -> Dlna.values("ConnectionIDs", "0");
            case "GetCurrentConnectionInfo" -> {
                if (!"0".equals(args.get("ConnectionID"))) throw new Dlna.Fault(706,"No such connection");
                yield Dlna.values("RcsID","0","AVTransportID","0","ProtocolInfo","http-get:*:*:*","PeerConnectionManager","","PeerConnectionID","-1","Direction","Input","Status","OK");
            }
            default -> throw new Dlna.Fault(401, "Invalid Action");
        };
        if (service.equals(Dlna.RC)) {
            switch (name) {
                case "GetVolume": return Dlna.values("CurrentVolume", Integer.toString(volume()));
                case "SetVolume":
                    int vol = integer(args.get("DesiredVolume"),0,100);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vol * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100f), 0); break;
                case "GetMute": return Dlna.values("CurrentMute", audio.isStreamMute(AudioManager.STREAM_MUSIC) ? "1" : "0");
                case "SetMute":
                    String mute = args.get("DesiredMute");
                    if (!List.of("0","1","true","false").contains(mute)) throw new Dlna.Fault(402,"Invalid mute value");
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, (mute.equals("1") || mute.equals("true")) ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0); break;
                case "ListPresets": return Dlna.values("CurrentPresetNameList","FactoryDefaults");
                case "SelectPreset": if (!"FactoryDefaults".equals(args.get("PresetName"))) throw new Dlna.Fault(701,"Invalid preset"); break;
                default: throw new Dlna.Fault(401,"Invalid Action");
            }
            changed(); return Map.of();
        }
        switch (name) {
            case "SetAVTransportURI":
                String uri = args.get("CurrentURI");
                if (uri == null || uri.isBlank()) { stopPlayback(); break; }
                URI parsed;
                try { parsed = new URI(uri); if(!List.of("http","https").contains(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null) throw new IllegalArgumentException(); }
                catch (Exception e) { throw new Dlna.Fault(714,"Only HTTP(S) media supported"); }
                cancelRetry(); cancelFormatProbe(); retries = 0; formatProbed = false; sessionStarted = 0; playingSince = 0; desiredPlay = false; pendingPresentation = false;
                selectedQuality = ""; setSpeedInternal(1f);
                player.stop(); player.clearMediaItems(); resetQuality();
                currentUri = uri; metadata = args.getOrDefault("CurrentURIMetaData", "");
                String declared = MediaProbe.mime(Xml.resourceMime(metadata)); mime = MediaProbe.initialMime(uri,declared);
                title = Xml.first(metadata, "title"); if (title.isBlank()) title = "小狗便利店投屏";
                received++; status = "已收到视频，等待播放指令"; error = ""; transportStatus = "OK";
                log("已收到第 " + received + " 次播放地址，来源：" + parsed.getHost() + (declared.isEmpty() ? "" : "，投屏标记：" + declared));
                if(!mime.equalsIgnoreCase(declared) && !mime.isEmpty()) log("按播放地址选择格式：" + mime);
                break;
            case "Play":
                float requestedSpeed = parseSpeed(args.get("Speed"));
                if(currentUri.isEmpty()) throw new Dlna.Fault(701,"No media");
                if (requestedSpeed != 1f && !playback().canSpeed()) throw new Dlna.Fault(717,"Speed unavailable for this source");
                setSpeedInternal(requestedSpeed);
                desiredPlay = true;
                if (!ensurePresentation()) break;
                foreground(true);
                if (player.getMediaItemCount() == 0 || player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED || player.getPlayerError() != null) prepareCurrent();
                else player.play();
                log("已收到播放指令"); break;
            case "Pause":
                if(currentUri.isEmpty()) throw new Dlna.Fault(701,"No media");
                desiredPlay = false; pendingPresentation = false; cancelRetry(); player.pause(); foreground(false); status="已暂停"; log("已暂停"); break;
            case "Stop": stopPlayback(); break;
            case "Seek":
                if (!List.of("REL_TIME","ABS_TIME").contains(args.get("Unit"))) throw new Dlna.Fault(710,"Seek mode unsupported");
                long target = Dlna.millis(args.get("Target"));
                Playback seek = playback();
                if (!seek.canSeek() || !seekPlayback(seek.generation(), target, seek.windowStart())) throw new Dlna.Fault(710,"Source does not support seeking");
                break;
            case "GetTransportInfo": return Dlna.values("CurrentTransportState",transportState(),"CurrentTransportStatus",transportStatus,"CurrentSpeed",speedProtocol(selectedSpeed));
            case "GetPositionInfo": return Dlna.values("Track", currentUri.isEmpty()?"0":"1", "TrackDuration",duration(),"TrackMetaData",metadata,"TrackURI",currentUri,"RelTime",Dlna.time(player.getCurrentPosition()),"AbsTime",Dlna.time(player.getCurrentPosition()),"RelCount","2147483647","AbsCount","2147483647");
            case "GetMediaInfo": return Dlna.values("NrTracks",currentUri.isEmpty()?"0":"1","MediaDuration",duration(),"CurrentURI",currentUri,"CurrentURIMetaData",metadata,"NextURI","","NextURIMetaData","","PlayMedium","NETWORK","RecordMedium","NOT_IMPLEMENTED","WriteStatus","NOT_IMPLEMENTED");
            case "GetDeviceCapabilities": return Dlna.values("PlayMedia","NETWORK","RecMedia","NOT_IMPLEMENTED","RecQualityModes","NOT_IMPLEMENTED");
            case "GetTransportSettings": return Dlna.values("PlayMode","NORMAL","RecQualityMode","NOT_IMPLEMENTED");
            case "GetCurrentTransportActions": return Dlna.values("Actions", transportActions());
            default: throw new Dlna.Fault(401,"Invalid Action");
        }
        changed(); return Map.of();
    }
    private static int integer(String value, int min, int max) throws Dlna.Fault { try { int n=Integer.parseInt(value); if(n<min || n>max)throw new NumberFormatException(); return n; } catch(Exception e){ throw new Dlna.Fault(402,"Invalid argument"); } }
    private int volume() { int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); return max == 0 ? 0 : Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max); }
    private String duration() { long value=player.getDuration(); return value==C.TIME_UNSET || value<0 || player.isCurrentMediaItemLive()?"00:00:00":Dlna.time(value); }
    private String transportState() {
        if(currentUri.isEmpty()) return "NO_MEDIA_PRESENT";
        if((probing || pendingPresentation) && desiredPlay) return "TRANSITIONING";
        if(player.getPlayerError()!=null || player.getPlaybackState()==Player.STATE_IDLE || player.getPlaybackState()==Player.STATE_ENDED) return "STOPPED";
        if(desiredPlay && player.getPlaybackState()==Player.STATE_BUFFERING) return "TRANSITIONING";
        return player.getPlayWhenReady()?"PLAYING":"PAUSED_PLAYBACK";
    }
    private String transportActions() { return currentUri.isEmpty()?"":"Play,Stop,Pause" + (playback().canSeek()?",Seek":""); }
    @Override public Map<String,String> event(String service) throws Exception {
        return onMain(() -> switch (service) {
            case Dlna.AV -> Dlna.values("TransportState",transportState(),"TransportStatus",transportStatus,"TransportPlaySpeed",speedProtocol(selectedSpeed),"AVTransportURI",currentUri,"AVTransportURIMetaData",metadata,"CurrentTrackURI",currentUri,"CurrentTrackMetaData",metadata,"NumberOfTracks",currentUri.isEmpty()?"0":"1","CurrentTrack",currentUri.isEmpty()?"0":"1","CurrentTrackDuration",duration(),"CurrentMediaDuration",duration(),"CurrentTransportActions",transportActions());
            case Dlna.RC -> Dlna.values("Volume",Integer.toString(volume()),"Mute",audio.isStreamMute(AudioManager.STREAM_MUSIC)?"1":"0");
            default -> Dlna.values("SourceProtocolInfo","","SinkProtocolInfo",Dlna.PROTOCOLS,"CurrentConnectionIDs","0");
        });
    }
    private void prepareCurrent() {
        if(!alive || currentUri.isEmpty() || !desiredPlay || probing || !ensurePresentation()) return;
        error=""; transportStatus="OK"; status="正在连接直播";
        MediaItem.Builder item = new MediaItem.Builder().setUri(currentUri)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8000).build())
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        if(!mime.isEmpty()) item.setMimeType(mime);
        player.setMediaItem(item.build()); player.prepare(); player.play(); changed();
    }
    private void inspectFailedMedia() {
        formatProbed=true; probing=true;
        int ticket=mediaGeneration; String uri=currentUri;
        status="正在识别直播实际格式";
        log("开始检查返回内容，最多读取 8 KB，不记录完整地址"); changed();
        formatJob=formatWork.submit(() -> {
            try {
                MediaProbe.Result result=MediaProbe.fetch(uri);
                main.post(() -> {
                    if(!alive || ticket!=mediaGeneration || !uri.equals(currentUri))return;
                    probing=false; formatJob=null;
                    log("格式检查：HTTP " + result.status() + "，响应标记：" + (result.contentType().isEmpty()?"未提供":result.contentType()) + "，实际内容：" + result.format().kind());
                    if(result.status()==401 || result.status()==403 || result.status()==404 || result.status()==410) { fail("直播地址已失效或服务器拒绝访问，请在 Z 视介重新投屏。"); return; }
                    if(result.status()<200 || result.status()>=300) { scheduleRetry("检查直播地址时服务器返回 HTTP " + result.status()); return; }
                    if(result.format().nonMedia()) { fail("收到的是网页或接口响应（" + result.format().kind() + "），不是可直接播放的视频；需要进一步适配这个地址。"); return; }
                    String detected=result.format().mime();
                    if(!detected.isEmpty() && !detected.equalsIgnoreCase(mime)) {
                        mime=detected; log("已纠正格式，改用 " + detected + " 播放");
                        if(desiredPlay)prepareCurrent(); else changed();
                    } else fail("返回内容仍无法解析，请提供连接详情中的格式检查记录。");
                });
            } catch(Exception e) {
                main.post(() -> {
                    if(!alive || ticket!=mediaGeneration || !uri.equals(currentUri))return;
                    probing=false;formatJob=null;
                    log("格式检查未完成：" + e.getClass().getSimpleName());
                    fail("无法确认直播实际格式，请重新投屏或提供连接详情。");
                });
            }
        });
    }
    private void cancelFormatProbe() { mediaGeneration++; probing=false; if(formatJob!=null){formatJob.cancel(true);formatJob=null;} }
    private void scheduleRetry(String reason) {
        cancelRetry();
        if(!desiredPlay) return;
        if(retries >= 3) { fail("直播连接未恢复，请在 Z 视介重新投屏。"); return; }
        int delay = new int[]{2,5,10}[retries++]; status = "连接中断，" + delay + " 秒后重试（" + retries + "/3）";
        error=""; log(reason + "，准备第 " + retries + " 次重连");
        pendingRetry=() -> { pendingRetry=null; if(alive && desiredPlay) prepareCurrent(); };
        main.postDelayed(pendingRetry,delay*1000L); changed();
    }
    private void cancelRetry() { if(pendingRetry!=null) { main.removeCallbacks(pendingRetry); pendingRetry=null; } }
    private void fail(String message) { cancelRetry(); desiredPlay=false; transportStatus="ERROR_OCCURRED"; status="播放未成功"; error=message; changed(); }
    private static int responseCode(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) if(e instanceof HttpDataSource.InvalidResponseCodeException) return ((HttpDataSource.InvalidResponseCodeException)e).responseCode;
        return 0;
    }
    public void stopPlayback() {
        desiredPlay=false; pendingPresentation=false; cancelRetry(); cancelFormatProbe(); selectedQuality=""; setSpeedInternal(1f); player.stop(); player.clearMediaItems(); resetQuality(); currentUri=""; metadata=""; title=""; mime="";
        error=""; transportStatus="OK"; sessionStarted=0; playingSince=0; status=endpoint.isEmpty()?"接收服务未就绪":"等待平板投屏"; foreground(false); log("已结束播放，等待下一次投屏"); changed();
    }
    public void togglePause() {
        if(currentUri.isEmpty())return;
        try { dispatch(Dlna.AV,desiredPlay?"Pause":"Play",Dlna.values("InstanceID","0","Speed",speedProtocol(selectedSpeed))); } catch(Exception ignored) { }
    }
    public void reconnect() { if(currentUri.isEmpty())return; retries=0; desiredPlay=true; cancelRetry(); prepareCurrent(); }
    /** A bounded window supplied by the actual source, never the TV's connection timer. */
    public Playback playback() {
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration < 0) duration = 0;
        boolean hasMedia = !currentUri.isEmpty() && !player.getCurrentTimeline().isEmpty();
        boolean live = hasMedia && player.isCurrentMediaItemLive();
        boolean seek = hasMedia && duration > 0 && player.isCurrentMediaItemSeekable()
                && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        long start = 0;
        if (hasMedia && live) {
            Timeline.Window window = player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
            start = window.windowStartTimeMs != C.TIME_UNSET ? window.windowStartTimeMs : window.getPositionInFirstPeriodMs();
        }
        return new Playback(mediaGeneration, Math.max(0, player.getCurrentPosition()), duration,
                Math.max(0, player.getBufferedPosition()), start, live, seek,
                hasMedia && duration > 0 && (!live || seek) && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                live && player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION), selectedSpeed);
    }
    public boolean seekPlayback(int session, long position, long originalWindowStart) {
        Playback current = playback();
        if (session != mediaGeneration || !current.canSeek()) return false;
        // Keep the selected broadcast instant when a DVR window slides during a drag.
        long adjusted = position + (current.live() ? originalWindowStart - current.windowStart() : 0);
        player.seekTo(Math.max(0, Math.min(current.duration(), adjusted)));
        changed(); return true;
    }
    public boolean selectSpeed(int session, float value) {
        if (session != mediaGeneration || !playback().canSpeed() || !validSpeed(value)) return false;
        setSpeedInternal(value); log("播放速度：" + speedLabel(value)); changed(); return true;
    }
    public boolean goLive(int session) {
        if (session != mediaGeneration || !playback().canGoLive()) return false;
        setSpeedInternal(1f); player.seekToDefaultPosition(); changed(); return true;
    }
    public static String speedLabel(float speed) { return (speed == (int)speed ? Integer.toString((int)speed) : Float.toString(speed)) + "×"; }
    private static boolean validSpeed(float value) { for (float speed : PLAYBACK_SPEEDS) if (speed == value) return true; return false; }
    private static String speedProtocol(float speed) {
        if (speed == .5f) return "1/2"; if (speed == .75f) return "3/4";
        if (speed == 1.25f) return "5/4"; if (speed == 1.5f) return "3/2";
        return speed == 2f ? "2" : "1";
    }
    private static float parseSpeed(String value) throws Dlna.Fault {
        try {
            String[] ratio = value.split("/", -1);
            float speed = ratio.length == 2 ? Float.parseFloat(ratio[0]) / Float.parseFloat(ratio[1]) : Float.parseFloat(value);
            if (ratio.length > 2 || !validSpeed(speed)) throw new IllegalArgumentException();
            return speed;
        } catch (Exception e) { throw new Dlna.Fault(717, "Unsupported playback speed"); }
    }
    private void setSpeedInternal(float speed) {
        selectedSpeed = speed;
        if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackSpeed(speed);
    }
    public QualityMenu qualityMenu() {
        return new QualityMenu(mediaGeneration, qualityChoices(), selectedQuality, player.getCurrentTracks().isEmpty());
    }
    private List<QualityCatalog.Choice> qualityChoices() {
        List<QualityCatalog.Track> tracks = new ArrayList<>();
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                tracks.add(new QualityCatalog.Track(g, i, format.width, format.height, group.isTrackSupported(i), group.isSelected()));
            }
        }
        return QualityCatalog.from(tracks);
    }
    private QualityCatalog.Choice findQuality(String key) {
        for (QualityCatalog.Choice choice : qualityChoices()) if (choice.key().equals(key)) return choice;
        return null;
    }
    public boolean selectQuality(int session, String key) {
        if (session != mediaGeneration || currentUri.isEmpty()) return false;
        if (key.isEmpty()) {
            resetQuality(); log("清晰度已设为自动"); changed(); return true;
        }
        QualityCatalog.Choice choice = findQuality(key);
        if (choice == null) return false;
        selectedQuality = key;
        applyQuality(choice);
        log("已选择清晰度：" + choice.label()); changed(); return true;
    }
    private void applyQuality(QualityCatalog.Choice choice) {
        int groupIndex = choice.preferredGroup();
        Tracks.Group group = player.getCurrentTracks().getGroups().get(groupIndex);
        TrackSelectionOverride override = new TrackSelectionOverride(group.getMediaTrackGroup(), choice.indices(groupIndex));
        if (override.equals(player.getTrackSelectionParameters().overrides.get(override.mediaTrackGroup))) return;
        // An explicit supported source track may exceed the TV's 1080p UI viewport.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(override).build());
    }
    private void resetQuality() {
        selectedQuality = "";
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
    }
    private String actualQuality() {
        // This reports decoder input size, not the requested track or UI/screen size.
        Format format = player.getVideoFormat();
        if (currentUri.isEmpty() || player.getPlaybackState() == Player.STATE_IDLE || format == null || format.width <= 0 || format.height <= 0) return "正在识别…";
        return format.width + "×" + format.height;
    }
    private String qualityMode() {
        if (selectedQuality.isEmpty()) return "自动";
        QualityCatalog.Choice choice = findQuality(selectedQuality);
        if (choice == null) return "自动";
        Format actual = player.getVideoFormat();
        return actual != null && actual.width == choice.width() && actual.height == choice.height()
                ? "手动" : "正在切换至 " + choice.label();
    }
    boolean hasPresentation() { return observer!=null; }
    boolean isShuttingDown() { return shuttingDown; }
    public ExoPlayer player() { return player; }
    public void observe(Runnable value) {
        observer=value;
        if(value!=null) {
            if(pendingPresentation && desiredPlay) { pendingPresentation=false; foreground(true); prepareCurrent(); }
            value.run();
        } else if(!shuttingDown && !currentUri.isEmpty()) {
            // Leaving a TV video surface must never leave invisible playback/audio running.
            stopPlayback();
        }
    }
    private boolean ensurePresentation() {
        if(observer!=null) { pendingPresentation=false; return true; }
        if(pendingPresentation)return false;
        pendingPresentation=true; status="已收到投屏，请打开电视上的本应用播放"; foreground(false);
        if(Build.VERSION.SDK_INT<29 || Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                log("已请求打开播放界面；若系统未切换，请手动打开本应用");
            } catch(RuntimeException e) { log("系统未允许打开播放界面，请手动打开本应用"); }
        } else log("后台已收到投屏，尚未授权自动打开，请手动打开本应用");
        return false;
    }
    public void shutdown() {
        shuttingDown=true;
        stopPlayback();
        stopSelf();
    }
    public Ui ui() { return new Ui(status,error,address,endpoint,title,received,String.join("\n",history),sessionStarted==0?"00:00:00":Dlna.time(SystemClock.elapsedRealtime()-sessionStarted),!currentUri.isEmpty(),actualQuality(),qualityMode(),mediaGeneration); }
    private void changed() { if(observer!=null) observer.run(); HttpServer server=http; if(server!=null)server.publish(); }
    private void log(String value) {
        if(Looper.myLooper()!=main.getLooper()){main.post(()->log(value));return;}
        if(!alive)return;
        history.add(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()) + "  " + value);
        while(history.size()>60)history.remove(0);
        if(observer!=null) observer.run();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && "cn.local.livingroomcast.STOP".equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy() {
        alive=false; generation.incrementAndGet(); cancelFormatProbe();formatWork.shutdownNow();observer=null; main.removeCallbacksAndMessages(null);
        if(connectivity!=null && networkCallback!=null) try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        networkWork.execute(this::closeNetwork); networkWork.shutdown();
        if(player!=null)player.release();
        if(multicastLock!=null && multicastLock.isHeld())multicastLock.release();
        if(wifiLock!=null && wifiLock.isHeld())wifiLock.release();
        stopForeground(true); super.onDestroy();
    }
}
