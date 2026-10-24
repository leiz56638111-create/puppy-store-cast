package com.airtv.receiver.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [VideoPipeline] with a genuine H.264 elementary stream and checks that frames
 * reach a real output surface.
 */
@RunWith(AndroidJUnit4::class)
class VideoPipelineTest {

    private val width = 640
    private val height = 360

    private lateinit var readerThread: HandlerThread
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private val imagesReceived = AtomicInteger()
    private val pipeline = VideoPipeline()

    private fun openSurface() {
        readerThread = HandlerThread("image-reader").apply { start() }
        val reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 6)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { imagesReceived.incrementAndGet() }
        }, Handler(readerThread.looper))
        imageReader = reader
        surface = reader.surface
    }

    @After
    fun tearDown() {
        pipeline.stop()
        surface?.release()
        imageReader?.close()
        if (::readerThread.isInitialized) readerThread.quitSafely()
    }

    @Test
    fun decodesAnH264StreamOntoTheSurface() {
        openSurface()
        val stream = H264TestStream.encode(width, height, frameCount = 30)
        assertTrue("test encoder produced no config", stream.any { it.isConfig })
        assertTrue("test encoder produced too few frames", stream.count { !it.isConfig } >= 20)

        pipeline.setCodec(h265 = false)
        pipeline.setSurface(surface)

        for (unit in stream) {
            pipeline.submit(
                ByteBuffer.wrap(unit.bytes), unit.bytes.size, unit.presentationTimeUs,
            )
        }

        val rendered = waitFor(5_000) { pipeline.framesRendered >= 20 }
        assertTrue(
            "only ${pipeline.framesRendered} frames rendered (dropped ${pipeline.framesDropped})",
            rendered,
        )
        assertTrue(
            "surface received only ${imagesReceived.get()} images",
            waitFor(3_000) { imagesReceived.get() >= 5 },
        )
    }

    /**
     * The shape a real iPhone sends: parameter sets prepended to the keyframe, never a
     * standalone codec-config access unit. This is what a Google TV showed a black screen on.
     */
    @Test
    fun decodesAStreamWhoseParameterSetsArePrependedToTheKeyframe() {
        openSurface()
        val stream = H264TestStream.asAirPlayShaped(
            H264TestStream.encode(width, height, frameCount = 30),
        )
        assertTrue(
            "the AirPlay-shaped stream must not contain a standalone config unit",
            stream.none { it.isConfig },
        )
        assertTrue("too few frames", stream.size >= 20)

        pipeline.setCodec(h265 = false)
        pipeline.setSurface(surface)
        for (unit in stream) {
            pipeline.submit(
                ByteBuffer.wrap(unit.bytes), unit.bytes.size, unit.presentationTimeUs,
            )
        }

        assertTrue(
            "only ${pipeline.framesRendered} frames rendered (dropped ${pipeline.framesDropped})",
            waitFor(6_000) { pipeline.framesRendered >= 20 },
        )
        assertTrue(
            "surface received only ${imagesReceived.get()} images",
            waitFor(3_000) { imagesReceived.get() >= 5 },
        )
    }

    @Test
    fun framesBeforeTheSurfaceArrivesAreDroppedNotCrashing() {
        val stream = H264TestStream.encode(width, height, frameCount = 10)
        // No surface yet: everything must be dropped without throwing.
        for (unit in stream) {
            pipeline.submit(ByteBuffer.wrap(unit.bytes), unit.bytes.size, unit.presentationTimeUs)
        }
        assertFalse("decoder must not start without a surface", pipeline.isRunning)
        assertTrue("nothing was counted as dropped", pipeline.framesDropped > 0)
        assertEquals(0L, pipeline.framesRendered)

        // Once the surface arrives the cached parameter sets start the decoder.
        openSurface()
        pipeline.setSurface(surface)
        assertTrue("decoder did not start once the surface arrived", pipeline.isRunning)
    }

    @Test
    fun decoderRestartsAfterTheSurfaceIsReplaced() {
        openSurface()
        val stream = H264TestStream.encode(width, height, frameCount = 20)
        pipeline.setCodec(h265 = false)
        pipeline.setSurface(surface)
        stream.forEach {
            pipeline.submit(ByteBuffer.wrap(it.bytes), it.bytes.size, it.presentationTimeUs)
        }
        assertTrue(waitFor(5_000) { pipeline.framesRendered > 0 })

        pipeline.setSurface(null)
        assertFalse(pipeline.isRunning)

        pipeline.setSurface(surface)
        assertTrue("decoder should restart from the cached config", pipeline.isRunning)

        val before = pipeline.framesRendered
        stream.forEach {
            pipeline.submit(ByteBuffer.wrap(it.bytes), it.bytes.size, it.presentationTimeUs + 1_000_000)
        }
        assertTrue(
            "no frames rendered after restart",
            waitFor(5_000) { pipeline.framesRendered > before },
        )
    }

    @Test
    fun aBufferedVideoBurstIsNotPlayedAtDecoderSpeed() {
        openSurface()
        val stream = H264TestStream.asAirPlayShaped(H264TestStream.encode(width, height, frameCount = 45))
        pipeline.setCodec(false)
        pipeline.setSurface(surface)
        val started = android.os.SystemClock.elapsedRealtime()
        stream.forEach { pipeline.submit(ByteBuffer.wrap(it.bytes), it.bytes.size, it.presentationTimeUs) }
        assertTrue("not enough frames rendered", waitFor(7000) { pipeline.framesRendered >= 35 })
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        android.util.Log.i("MirrorRegression", "45-frame burst: $elapsed ms, rendered=${pipeline.framesRendered}")
        assertTrue("a 1.5-second video burst was fast-forwarded in ${elapsed}ms", elapsed >= 1000)
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
