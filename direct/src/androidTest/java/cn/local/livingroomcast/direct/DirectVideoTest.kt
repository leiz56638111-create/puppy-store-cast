// SPDX-License-Identifier: GPL-3.0-only
package cn.local.livingroomcast.direct
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airtv.receiver.airplay.NativeAirPlay
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.Socket
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DirectVideoTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private var scenario:ActivityScenario<DirectActivity>?=null
 private lateinit var service:DirectService
 private val sockets=mutableListOf<Wire>()
 private fun <T> ui(action:()->T):T {if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return action();var value:T?=null;inst.runOnMainSync{value=action()};@Suppress("UNCHECKED_CAST")return value as T}
 private fun await(label:String,test:()->Boolean){val until=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<until){if(test())return;Thread.sleep(100)};fail("$label; "+runCatching{ui{service.diagnostic}}.getOrDefault("not bound"))}
 @Before fun open(){scenario=ActivityScenario.launch(DirectActivity::class.java);await("bind direct service"){var found=false;scenario!!.onActivity{a->val f=DirectActivity::class.java.getDeclaredField("service").apply{isAccessible=true};(f.get(a) as? DirectService)?.let{service=it;found=true}};found && service.port>0};assertTrue(NativeAirPlay.nativeIsRunning())}
 @After fun close(){sockets.forEach{runCatching{it.socket.close()}};scenario?.close();await("server stopped"){!NativeAirPlay.nativeIsRunning()}}
 private fun client()=Wire(Socket("127.0.0.1",service.port).apply{soTimeout=10000}).also{sockets+=it}
 private data class Reply(val status:Int,val body:ByteArray)
 private class Wire(val socket:Socket){
  private val input=BufferedInputStream(socket.getInputStream())
  private fun line():String{val b=java.io.ByteArrayOutputStream();while(true){val v=input.read();if(v<0)throw java.io.EOFException();if(v==10)break;if(v!=13)b.write(v)};return b.toString("UTF-8")}
  fun read():Reply{val first=line();var length=0;while(true){val row=line();if(row.isEmpty())break;if(row.startsWith("Content-Length:",true))length=row.substringAfter(':').trim().toInt()};val body=ByteArray(length);var n=0;while(n<length){val read=input.read(body,n,length-n);if(read<0)throw java.io.EOFException();n+=read};return Reply(if(first.startsWith("HTTP/"))first.split(' ')[1].toInt() else 0,body)}
  fun send(method:String,path:String,body:ByteArray=byteArrayOf(),type:String="text/parameters",session:String="test-session",headers:String=""):Reply{val header="$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Apple-Session-ID: $session\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$headers\r\n";socket.getOutputStream().apply{write(header.toByteArray());write(body);flush()};return read()}
 }
 private fun asset(name:String)=inst.context.assets.open(name).use{it.readBytes()}
 private fun play(w:Wire,path:String="http://10.0.2.2:18099/video.mp4",session:String="test-session"){assertEquals(200,w.send("POST","/play","Content-Location: $path\r\nStart-Position: 0\r\n".toByteArray(),session=session).status);await("real first video frame"){ui{service.frameShown && service.player.isPlaying}}}
 private fun capture(name:String){inst.waitForIdleSync();Thread.sleep(800);val folder=File(inst.targetContext.filesDir,"direct-tests").apply{mkdirs()};val b=inst.uiAutomation.takeScreenshot();File(folder,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun directMp4HasRealAudioClockPauseSeekAndStop(){
  val w=client();play(w);capture("direct-playing.png")
  await("audio decoded"){ui{service.player.audioFormat!=null && (service.player.audioDecoderCounters?.renderedOutputBufferCount?:0)>0}}
  // Exercise the visible player's touch pause/resume, not only HTTP controls.
  scenario!!.onActivity { a -> val field=DirectActivity::class.java.getDeclaredField("video").apply{isAccessible=true};val view=field.get(a) as androidx.media3.ui.PlayerView;view.showController();assertTrue(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause).performClick()) }
  await("touch pause"){ui{!service.player.playWhenReady}}
  scenario!!.onActivity { a -> val field=DirectActivity::class.java.getDeclaredField("video").apply{isAccessible=true};val view=field.get(a) as androidx.media3.ui.PlayerView;assertTrue(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause).performClick()) }
  await("touch resume"){ui{service.player.isPlaying}}
  val first=ui{service.player.currentPosition};Thread.sleep(700);assertTrue(ui{service.player.currentPosition}>first+300)
  val info=w.send("GET","/playback-info");assertEquals(200,info.status);assertTrue(String(info.body).contains("<key>duration</key>"));assertTrue(String(info.body).contains("<true/>"))
  assertEquals(200,w.send("POST","/rate?value=0").status);await("pause"){ui{!service.player.playWhenReady}};val paused=ui{service.player.currentPosition};Thread.sleep(400);assertTrue(kotlin.math.abs(ui{service.player.currentPosition}-paused)<200)
  assertEquals(200,w.send("POST","/scrub?position=12").status);await("seek while paused"){ui{kotlin.math.abs(service.player.currentPosition-12000)<1000 && !service.player.playWhenReady}}
  val scrub=w.send("GET","/scrub");assertTrue(String(scrub.body).contains("duration:"))
  assertEquals(200,w.send("POST","/rate?value=1").status);await("resume"){ui{service.player.isPlaying}}
  assertEquals(200,w.send("POST","/stop").status);await("stop clears media"){ui{!service.hasMedia && service.player.mediaItemCount==0}}
 }
 @Test fun sameSessionCanSwitchToANewVideoAddress(){val w=client();play(w);play(w,path="http://10.0.2.2:18099/video.mp4?second=1");assertTrue(ui{service.player.currentMediaItem!!.localConfiguration!!.uri.toString()}.endsWith("?second=1"));assertEquals(2,ui{service.requests})}
 @Test fun binaryAndGenericHlsRequestsPlayWithoutAppAllowlist(){val w=client();assertEquals(200,w.send("POST","/play",asset("generic-hls.bplist"),"application/x-apple-binary-plist").status);await("generic HLS frame"){ui{service.frameShown}};assertEquals("HTTP(S) 视频直投",ui{service.sourceKind})}
 @Test fun malformedControlsAndOldSessionCannotAffectNewVideo(){val w=client();assertEquals(400,w.send("POST","/play","bad-data".toByteArray()).status);await("rejected raw play request is visible"){ui{service.incomingPlayRequests==1 && service.rejectedPlayRequests==1}};play(w,session="first-session");play(w,session="second-session");assertEquals(400,w.send("POST","/rate?value=0",session="first-session").status);assertEquals(400,w.send("POST","/scrub?position=nan",session="second-session").status);assertEquals(400,w.send("POST","/rate?",session="second-session").status);assertEquals(409,w.send("POST","/stop",session="first-session").status);assertTrue(ui{service.player.playWhenReady});assertFalse(ui{service.diagnostic}.contains("http://"));assertTrue(NativeAirPlay.nativeIsRunning())}
 @Test fun reverseHttpNegotiatesPlaylistsBeforeActualVideoPlayback(){
  val reverse=client();assertEquals(101,reverse.send("POST","/reverse",headers="Connection: Upgrade\r\nUpgrade: PTTH/1.0\r\nX-Apple-Purpose: event\r\n").status)
  val control=client();assertEquals(200,control.send("POST","/play",asset("proxy-play.bplist"),"application/x-apple-binary-plist").status)
  val master=reverse.read();assertTrue(String(master.body).contains("master.m3u8"))
  assertEquals(200,control.send("POST","/action",asset("proxy-master.bplist"),"application/x-apple-binary-plist").status)
  val media=reverse.read();assertTrue(String(media.body).contains("variant.m3u8"))
  assertEquals(200,control.send("POST","/action",asset("proxy-media.bplist"),"application/x-apple-binary-plist").status)
  await("proxied HLS rendered"){ui{service.frameShown && service.player.isPlaying}}
  assertEquals("发送端协商的 HLS 播放列表",ui{service.sourceKind});assertTrue(ui{service.player.duration}>10000)
 }
 @Test fun standaloneIdentityAndDiscoveryFeaturesAreHonest(){val w=client();val reply=w.send("GET","/server-info");assertEquals(200,reply.status);val xml=String(reply.body);val mask=Regex("<key>features</key>\\s*<integer>([0-9]+)</integer>").find(xml)!!.groupValues[1].toLong();assertTrue(mask and 1L!=0L);assertTrue(mask and 16L!=0L);assertEquals(0L,mask and 4L);assertEquals(0L,mask and 128L);assertEquals("cn.local.livingroomcast.airplayvideo",inst.targetContext.packageName);capture("direct-waiting.png")}
 @Test fun mediaHandshakeAndAudioAloneMustNotBeReportedAsMirrorVideo(){
  service.onSessionStart();await("generic media connection waiting for video"){ui{service.status.contains("等待 App 发送视频")}};assertFalse(ui{service.diagnostic}.contains("收到屏幕镜像请求"));assertEquals(0,ui{service.requests})
  service.onAudioFormat(4,false,true);await("audio negotiation remains separate from mirror evidence"){ui{service.diagnostic.contains("usingScreen=false")}};assertTrue(ui{service.diagnostic}.contains("镜像视频数据 尚未收到"));assertEquals(0,ui{service.requests})
  val w=client();play(w);assertEquals(1,ui{service.requests});assertEquals(1,ui{service.incomingPlayRequests})
 }
 @Test fun homeStopsReceptionAndOpeningRestarts(){scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);await("stopped when leaving"){!NativeAirPlay.nativeIsRunning()};scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);await("restart"){NativeAirPlay.nativeIsRunning()}}
 @Test fun actualBonjourQueryFindsTheDirectReceiver(){
  val group=java.net.InetAddress.getByName("224.0.0.251")
  java.net.MulticastSocket(null).use { socket ->
   socket.reuseAddress=true;socket.bind(java.net.InetSocketAddress(5353));socket.soTimeout=1000
   @Suppress("DEPRECATION") socket.joinGroup(group)
   val bytes=java.io.ByteArrayOutputStream();repeat(12){bytes.write(if(it==5)1 else 0)}
   for(label in listOf("_airplay","_tcp","local")){bytes.write(label.length);bytes.write(label.toByteArray())};bytes.write(0);bytes.write(byteArrayOf(0,12,0,1))
   val until=SystemClock.elapsedRealtime()+10000;var found=false
   while(SystemClock.elapsedRealtime()<until && !found){socket.send(java.net.DatagramPacket(bytes.toByteArray(),bytes.size(),group,5353));try{val buffer=ByteArray(8192);val packet=java.net.DatagramPacket(buffer,buffer.size);socket.receive(packet);found=String(buffer,0,packet.length,Charsets.UTF_8).contains(service.name)}catch(_:java.net.SocketTimeoutException){}}
   assertTrue("Bonjour reply must contain the direct receiver name",found)
  }
 }

}
