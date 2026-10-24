package com.airtv.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android hides the real MAC address from apps (API 24+), but the AirPlay protocol needs a
 * stable 6-byte device id: iOS keys its "trusted receiver" state off it, so it must survive
 * app restarts and must not collide with a real NIC.
 */
class DeviceIdentityTest {

    @Test
    fun `mac address has canonical colon separated form`() {
        val mac = DeviceIdentity.macAddressFrom("some-android-id")
        assertTrue("unexpected mac: $mac", mac.matches(Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")))
    }

    @Test
    fun `mac address is stable for the same seed`() {
        assertEquals(
            DeviceIdentity.macAddressFrom("abc123"),
            DeviceIdentity.macAddressFrom("abc123"),
        )
    }

    @Test
    fun `mac address differs between devices`() {
        assertNotEquals(
            DeviceIdentity.macAddressFrom("abc123"),
            DeviceIdentity.macAddressFrom("abc124"),
        )
    }

    @Test
    fun `first octet is a locally administered unicast address`() {
        for (seed in listOf("a", "b", "living-room-tv", "", "0123456789abcdef")) {
            val firstOctet = DeviceIdentity.macAddressFrom(seed)
                .substringBefore(':')
                .toInt(16)
            assertEquals("multicast bit must be clear for seed '$seed'", 0, firstOctet and 0x01)
            assertEquals("local bit must be set for seed '$seed'", 0x02, firstOctet and 0x02)
        }
    }

    @Test
    fun `service name falls back when the device name is blank`() {
        assertEquals("AirTV", DeviceIdentity.serviceName("   ", fallback = "AirTV"))
        assertEquals("AirTV", DeviceIdentity.serviceName("", fallback = "AirTV"))
    }

    @Test
    fun `service name is trimmed`() {
        assertEquals("Living Room", DeviceIdentity.serviceName("  Living Room  ", "AirTV"))
    }

    @Test
    fun `service name is truncated to the dns sd limit`() {
        val long = "x".repeat(100)
        assertEquals(63, DeviceIdentity.serviceName(long, "AirTV").length)
    }
}
