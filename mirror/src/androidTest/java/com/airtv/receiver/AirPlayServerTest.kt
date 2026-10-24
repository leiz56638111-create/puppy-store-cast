package com.airtv.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.NativeAirPlay
import com.airtv.receiver.airplay.ReceiverState
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boots the real native AirPlay server inside the app and talks to it over a socket,
 * exercising the RTSP daemon, the plist encoder and the pairing key store — everything an
 * iPhone touches before it starts streaming.
 */
@RunWith(AndroidJUnit4::class)
class AirPlayServerTest {

    private lateinit var context: Context
    private lateinit var receiver: AirPlayReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "airplay_pairing.key").delete()
        receiver = AirPlayReceiver(context)
    }

    @After
    fun tearDown() {
        receiver.stop()
    }

    @Test
    fun serverStartsAndReportsAListeningPort() {
        assertTrue("nativeStart failed: ${receiver.state}", receiver.start())
        val state = receiver.state
        assertTrue("unexpected state $state", state is ReceiverState.Advertising)
        val port = (state as ReceiverState.Advertising).port
        assertTrue("bad port $port", port in 1..65535)
        assertTrue(NativeAirPlay.nativeIsRunning())
    }

    @Test
    fun startingPersistsThePairingKeySoSendersStayPaired() {
        assertTrue(receiver.start())
        val keyFile = File(context.filesDir, "airplay_pairing.key")
        assertTrue("pairing key was not written", keyFile.exists())
        assertTrue("pairing key is empty", keyFile.length() > 0)
    }

    @Test
    fun infoRequestIsAnsweredWithABinaryPlist() {
        assertTrue(receiver.start())
        val port = (receiver.state as ReceiverState.Advertising).port

        val response = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    (
                        "GET /info RTSP/1.0\r\n" +
                            "CSeq: 0\r\n" +
                            "User-Agent: AirPlay/665.13.1\r\n" +
                            "Content-Length: 0\r\n\r\n"
                        ).toByteArray()
                )
                flush()
            }
            readRtspResponse(socket).also {
                assertTrue("a metadata connection must not start media", receiver.state is ReceiverState.Advertising)
            }
        }

        assertTrue(
            "unexpected status line: ${response.statusLine}",
            response.statusLine.contains("200"),
        )
        assertTrue("empty body", response.body.isNotEmpty())
        assertEquals(
            "response body is not a binary plist",
            "bplist00",
            String(response.body.copyOf(8)),
        )
    }

    @Test
    fun optionsRequestAdvertisesTheSupportedMethods() {
        assertTrue(receiver.start())
        val port = (receiver.state as ReceiverState.Advertising).port

        val response = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                flush()
            }
            readRtspResponse(socket)
        }

        assertTrue(response.statusLine.contains("200"))
        val public = response.headers["public"] ?: ""
        for (method in listOf("SETUP", "RECORD", "TEARDOWN", "GET_PARAMETER")) {
            assertTrue("OPTIONS did not advertise $method (got '$public')", public.contains(method))
        }
    }

    @Test
    fun anInfoProbeMustNotEndAnExistingMediaSession() {
        assertTrue(receiver.start())
        val port = (receiver.state as ReceiverState.Advertising).port
        receiver.onSessionStart()
        receiver.onAudioFormat(4, usingScreen = true, isMedia = false)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
            readRtspResponse(socket)
        }
        Thread.sleep(400)
        assertTrue("a metadata probe incorrectly ended the media session: ${receiver.state}", receiver.state is ReceiverState.Streaming)
    }

    private class RtspResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun readRtspResponse(socket: Socket): RtspResponse {
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            header.append(b.toChar())
        }
        val lines = header.toString().trim().split("\r\n")
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else {
                line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }
        }.toMap()

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return RtspResponse(lines.firstOrNull() ?: "", headers, body.copyOf(read))
    }
}
