package com.clonewolf.esphomeble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImprovProtocolTest {
    @Test
    fun wifiSettingsUsesLengthPrefixAndAdditiveChecksum() {
        val packet = ImprovProtocol.wifiSettings("A", "B")

        assertArrayEquals(
            byteArrayOf(0x01, 0x04, 0x01, 0x41, 0x01, 0x42, 0x8a.toByte()),
            packet
        )
    }

    @Test
    fun wifiSettingsEnforcesUtf8ByteLimits() {
        assertTrue(runCatching { ImprovProtocol.wifiSettings("测".repeat(10), "") }.isSuccess)
        assertFalse(runCatching { ImprovProtocol.wifiSettings("测".repeat(11), "") }.isSuccess)
    }

    @Test
    fun responseParserExtractsTripletsAndDeduplicatesNetworks() {
        val response = response(
            ImprovProtocol.GET_WIFI_NETWORKS,
            listOf("Home", "-42", "[WPA2-PSK]"),
            listOf("Home", "-60", "[WPA2-PSK]")
        )

        val result = ImprovProtocol.parseResponse(response)
        val networks = ImprovProtocol.parseWifiNetworks(result)

        assertEquals(1, networks.size)
        assertEquals("Home", networks.single().ssid)
        assertEquals(-42, networks.single().rssi)
        assertEquals("[WPA2-PSK]", networks.single().security)
    }

    @Test
    fun responseParserRejectsInvalidChecksumAndLength() {
        val valid = response(ImprovProtocol.SEND_WIFI, "http://device".let(::listOf))

        assertTrue(runCatching { ImprovProtocol.parseResponse(valid.copyOf().apply { this[lastIndex]++ }) }.isFailure)
        assertTrue(runCatching { ImprovProtocol.parseResponse(valid.copyOf().apply { this[1]++ }) }.isFailure)
    }

    @Test
    fun wifiScanRequiresCapabilityBit() {
        assertFalse(ImprovProtocol.supportsWifiScan(ImprovProtocol.CAPABILITY_IDENTIFY))
        assertTrue(ImprovProtocol.supportsWifiScan(ImprovProtocol.CAPABILITY_WIFI_SCAN))
    }

    private fun response(command: Int, vararg values: String): ByteArray {
        val payload = values.flatMap { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            listOf(bytes.size.toByte()) + bytes.toList()
        }.toByteArray()
        val packet = byteArrayOf(command.toByte(), payload.size.toByte()) + payload + byteArrayOf(0)
        packet[packet.lastIndex] = packet.dropLast(1).sumOf { it.toInt() and 0xff }.toByte()
        return packet
    }
}
