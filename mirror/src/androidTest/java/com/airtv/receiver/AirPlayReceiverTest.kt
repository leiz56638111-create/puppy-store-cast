package com.airtv.receiver

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.media.H264TestStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same callback sequence the native library performs during a mirroring
 * session, with direct ByteBuffers over real H.264 data, and checks the receiver renders
 * and reports state correctly.
 */
@RunWith(AndroidJUnit4::class)
class AirPlayReceiverTest {

    private val width = 640
    private val height = 360

    private lateinit var context: Context
    private lateinit var receiver: AirPlayReceiver
    private lateinit var readerThread: HandlerThread
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private val imagesReceived = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = AirPlayReceiver(context)
        readerThread = HandlerThread("receiver-image-reader").apply { start() }
        val reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 6)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { imagesReceived.incrementAndGet() }
        }, Handler(readerThread.looper))
        imageReader = reader
        surface = reader.surface
    }

    @After
    fun tearDown() {
        receiver.stop()
        surface?.release()
        imageReader?.close()
        readerThread.quitSafely()
    }

    @Test
    fun aMirroringSessionRendersFramesAndTracksState() {
        // Shaped the way a real iPhone sends it: parameter sets prepended to the keyframe.
        val stream = H264TestStream.asAirPlayShaped(
            H264TestStream.encode(width, height, frameCount = 30),
        )

        receiver.setSurface(surface)
        assertTrue(receiver.onClientConnect("Michal's iPhone", "iPhone15,2", "aa:bb:cc:dd:ee:ff"))
        receiver.onSessionStart()

        val streaming = receiver.state
        assertTrue("expected Streaming, got $streaming", streaming is ReceiverState.Streaming)
        assertEquals("Michal's iPhone", (streaming as ReceiverState.Streaming).clientName)

        assertEquals(0, receiver.onVideoCodec(isH265 = false))
        receiver.onVideoSize(1920, 1080)
        receiver.onAudioFormat(8, usingScreen = true, isMedia = false) // mirroring

        for (unit in stream) {
            // The native layer hands us direct buffers over its own memory.
            val direct = ByteBuffer.allocateDirect(unit.bytes.size).apply {
                put(unit.bytes)
                position(0)
            }
            receiver.onVideoData(direct, unit.bytes.size, unit.presentationTimeUs)
        }

        assertTrue(
            "rendered ${receiver.videoFramesRendered}, dropped ${receiver.videoFramesDropped}",
            waitFor(6_000) { receiver.videoFramesRendered >= 20 },
        )
        assertTrue(
            "surface saw only ${imagesReceived.get()} frames",
            waitFor(3_000) { imagesReceived.get() >= 5 },
        )

        // A sender may renegotiate its audio route while video is still arriving.
        receiver.onAudioFormat(4, usingScreen = false, isMedia = true)
        assertTrue("audio negotiation covered active video", receiver.state is ReceiverState.Streaming)
        assertTrue(receiver.audioDiagnostics.contains("usingScreen=false"))
        assertTrue("audio-only recovery must not disconnect video", receiver.recoverAudio())
        assertTrue(receiver.state is ReceiverState.Streaming)

        receiver.onVolume(-12f)
        receiver.onVideoFlush()
        receiver.onAudioFlush()

        receiver.onSessionEnd()
        assertTrue(
            "expected Advertising after the session ended, got ${receiver.state}",
            receiver.state is ReceiverState.Advertising,
        )
    }

    @Test
    fun videoStopEndsTheSessionWithoutCrashing() {
        val stream = H264TestStream.asAirPlayShaped(
            H264TestStream.encode(width, height, frameCount = 10),
        )
        receiver.setSurface(surface)
        receiver.onSessionStart()
        receiver.onVideoCodec(isH265 = false)
        stream.forEach {
            val direct = ByteBuffer.allocateDirect(it.bytes.size).apply {
                put(it.bytes)
                position(0)
            }
            receiver.onVideoData(direct, it.bytes.size, it.presentationTimeUs)
        }
        receiver.onVideoStop()
        assertTrue(receiver.state is ReceiverState.Advertising)

        // A late frame after the stop must be dropped, not crash.
        val late = ByteBuffer.allocateDirect(16).apply { position(0) }
        receiver.onVideoData(late, 16, 0)
    }

    /**
     * What happens when you press AirPlay inside a video player instead of using Screen
     * Mirroring: iOS opens an audio-only session (ALAC) and never sends video. The TV must
     * explain that rather than showing a black screen.
     */
    @Test
    fun anAudioOnlyAlacSessionIsReportedInsteadOfGoingBlank() {
        receiver.setSurface(surface)
        assertTrue(receiver.onClientConnect("Ajfon Michała", "iPhone17,2", "fe:76:fd:f1:48:01"))
        receiver.onSessionStart()
        receiver.onAudioFormat(2, usingScreen = false, isMedia = true)

        val state = receiver.state
        assertTrue("expected AudioOnly, got $state", state is ReceiverState.AudioOnly)
        state as ReceiverState.AudioOnly
        assertEquals("Ajfon Michała", state.clientName)
        assertEquals("ALAC (Apple Lossless)", state.codec)
        assertEquals(false, state.playable)
    }

    @Test
    fun anAudioOnlyAacSessionIsReportedAsPlayable() {
        receiver.onSessionStart()
        receiver.onAudioFormat(4, usingScreen = false, isMedia = true)
        val state = receiver.state
        assertTrue("expected AudioOnly, got $state", state is ReceiverState.AudioOnly)
        assertEquals(true, (state as ReceiverState.AudioOnly).playable)
    }

    @Test
    fun aMirroringSessionIsNotReportedAsAudioOnly() {
        receiver.setSurface(surface)
        receiver.onSessionStart()
        receiver.onAudioFormat(8, usingScreen = true, isMedia = false)
        assertTrue(
            "mirroring must stay in Streaming, got ${receiver.state}",
            receiver.state is ReceiverState.Streaming,
        )
    }

    @Test
    fun theAdvertisedDisplayIsAUsableLandscapeMode() {
        val display = receiver.advertisedDisplay
        assertTrue("width ${display.width}", display.width in 1280..3840)
        assertTrue("height ${display.height}", display.height in 720..2160)
        assertTrue("fps ${display.fps}", display.fps in 24..60)
        assertTrue("not landscape: $display", display.width >= display.height)
        assertTrue(receiver.advertisedDisplay.width <= 1920)
        assertTrue(receiver.advertisedDisplay.height <= 1080)
        assertEquals(30, receiver.advertisedDisplay.fps)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
