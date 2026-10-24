package com.airtv.receiver

import android.content.Context
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An iPhone only offers a receiver in its AirPlay menu if the receiver answers multicast DNS
 * queries. This test asks the same questions an iPhone asks — a PTR query for
 * `_airplay._tcp.local` and `_raop._tcp.local` — and checks our responder answers.
 */
@RunWith(AndroidJUnit4::class)
class MdnsAdvertisementTest {

    private lateinit var context: Context
    private lateinit var receiver: AirPlayReceiver
    private var multicastLock: WifiManager.MulticastLock? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = AirPlayReceiver(context)
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("mdns-test").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @After
    fun tearDown() {
        receiver.stop()
        multicastLock?.takeIf { it.isHeld }?.release()
    }

    @Test
    fun airplayServiceAnswersMulticastQueries() {
        assertTrue(receiver.start())
        val name = (receiver.state as ReceiverState.Advertising).name
        assertTrue(answersQueryFor("_airplay._tcp", name))
    }

    @Test
    fun raopServiceAnswersMulticastQueries() {
        assertTrue(receiver.start())
        // The RAOP instance name is "<deviceid>@<name>", so finding the receiver name in a
        // _raop._tcp response proves the AirTunes service is published too.
        val name = (receiver.state as ReceiverState.Advertising).name
        assertTrue(answersQueryFor("_raop._tcp", name))
    }

    /**
     * Sends a PTR query for [service] and waits for a response mentioning [expected].
     * The responder replies to the multicast group, so we listen on port 5353 as well.
     */
    private fun answersQueryFor(service: String, expected: String): Boolean {
        val group = InetAddress.getByName("224.0.0.251")
        MulticastSocket(5353).use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 1_000
            socket.joinGroup(group)

            val query = ptrQuery(service)
            val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
            var lastSend = 0L
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - lastSend > 800) {
                    socket.send(DatagramPacket(query, query.size, group, 5353))
                    lastSend = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val payload = String(
                    packet.data, packet.offset, packet.length, Charsets.UTF_8,
                )
                val isResponse = packet.length > 3 &&
                    (packet.data[packet.offset + 2].toInt() and 0x80) != 0
                if (isResponse && payload.contains(service.substringBefore('.')) &&
                    payload.contains(expected)
                ) {
                    return true
                }
            }
            return false
        }
    }

    private fun ptrQuery(service: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0)) // transaction id
        out.write(byteArrayOf(0, 0)) // flags: standard query
        out.write(byteArrayOf(0, 1)) // qdcount
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0)) // an/ns/ar counts
        for (label in service.split('.') + listOf("local")) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0) // end of name
        out.write(byteArrayOf(0, 12)) // qtype PTR
        out.write(byteArrayOf(0, 1)) // qclass IN
        return out.toByteArray()
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 8_000L
    }
}
