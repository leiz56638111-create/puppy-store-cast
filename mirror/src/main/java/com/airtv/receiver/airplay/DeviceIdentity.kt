package com.airtv.receiver.airplay

import java.security.MessageDigest

/** Stable identity values advertised over Bonjour. */
object DeviceIdentity {

    /** DNS-SD instance names are limited to 63 bytes. */
    private const val MAX_SERVICE_NAME_LENGTH = 63

    /**
     * Derives a deterministic, locally administered unicast MAC address from [seed]
     * (normally Settings.Secure.ANDROID_ID). iOS remembers paired receivers by this id.
     */
    fun macAddressFrom(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val octets = digest.copyOf(6)
        // clear the multicast bit, set the locally-administered bit
        octets[0] = ((octets[0].toInt() and 0xFE) or 0x02).toByte()
        return octets.joinToString(":") { "%02x".format(it) }
    }

    fun serviceName(deviceName: String, fallback: String): String {
        val trimmed = deviceName.trim()
        val name = trimmed.ifEmpty { fallback }
        return if (name.length > MAX_SERVICE_NAME_LENGTH) {
            name.substring(0, MAX_SERVICE_NAME_LENGTH)
        } else {
            name
        }
    }
}
