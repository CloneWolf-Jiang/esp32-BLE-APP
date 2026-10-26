package com.clonewolf.esphomeble

import java.nio.charset.Charset
import java.util.UUID

internal object ImprovProtocol {
    val SERVICE = UUID.fromString("00467768-6228-2272-4663-277478268000")
    val STATUS = UUID.fromString("00467768-6228-2272-4663-277478268001")
    val ERROR = UUID.fromString("00467768-6228-2272-4663-277478268002")
    val RPC_COMMAND = UUID.fromString("00467768-6228-2272-4663-277478268003")
    val RPC_RESULT = UUID.fromString("00467768-6228-2272-4663-277478268004")
    val CAPABILITIES = UUID.fromString("00467768-6228-2272-4663-277478268005")

    const val AUTHORIZATION_REQUIRED = 0x01
    const val AUTHORIZED = 0x02
    const val PROVISIONING = 0x03
    const val PROVISIONED = 0x04

    const val NO_ERROR = 0x00
    const val INVALID_RPC = 0x01
    const val UNKNOWN_RPC = 0x02
    const val UNABLE_TO_CONNECT = 0x03
    const val NOT_AUTHORIZED = 0x04

    const val SEND_WIFI = 0x01
    const val IDENTIFY = 0x02
    const val GET_DEVICE_INFO = 0x03
    const val GET_WIFI_NETWORKS = 0x04

    const val CAPABILITY_IDENTIFY = 0x01
    const val CAPABILITY_DEVICE_INFO = 0x02
    const val CAPABILITY_WIFI_SCAN = 0x04

    private val UTF8: Charset = Charsets.UTF_8

    data class RpcResult(val command: Int, val values: List<String>)
    data class WifiNetwork(val ssid: String, val rssi: Int, val security: String)

    fun supportsWifiScan(capabilities: Int): Boolean =
        capabilities and CAPABILITY_WIFI_SCAN != 0

    fun wifiSettings(ssid: String, password: String): ByteArray {
        val ssidBytes = ssid.toByteArray(UTF8)
        val passwordBytes = password.toByteArray(UTF8)
        require(ssidBytes.isNotEmpty() && ssidBytes.size <= 32)
        require(passwordBytes.size <= 63 ||
            (passwordBytes.size == 64 && passwordBytes.all { it.toInt().toChar().digitToIntOrNull(16) != null }))
        val payloadLength = ssidBytes.size + passwordBytes.size + 2
        require(payloadLength <= 255)
        return frame(SEND_WIFI, byteArrayOf(ssidBytes.size.toByte()) + ssidBytes +
            byteArrayOf(passwordBytes.size.toByte()) + passwordBytes)
    }

    fun command(command: Int): ByteArray = frame(command, byteArrayOf())

    fun parseResponse(packet: ByteArray): RpcResult {
        require(packet.size >= 3) { "RPC response is too short" }
        val command = packet[0].toInt() and 0xff
        val payloadLength = packet[1].toInt() and 0xff
        require(packet.size == payloadLength + 3) { "RPC response length mismatch" }
        require(checksum(packet, packet.lastIndex) == (packet.last().toInt() and 0xff)) {
            "RPC response checksum mismatch"
        }
        val values = ArrayList<String>()
        var cursor = 2
        val end = 2 + payloadLength
        while (cursor < end) {
            val length = packet[cursor++].toInt() and 0xff
            require(cursor + length <= end) { "RPC response string exceeds payload" }
            values += String(packet, cursor, length, UTF8)
            cursor += length
        }
        return RpcResult(command, values)
    }

    fun parseWifiNetworks(result: RpcResult): List<WifiNetwork> {
        require(result.command == GET_WIFI_NETWORKS) { "Unexpected RPC response" }
        require(result.values.size % 3 == 0) { "Incomplete Wi-Fi network entry" }
        return result.values.chunked(3).map { entry ->
            val rssi = entry[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid Wi-Fi signal value")
            WifiNetwork(entry[0], rssi, entry[2])
        }.groupBy { it.ssid }.map { (_, entries) -> entries.maxBy { it.rssi } }
    }

    fun isImprovAdvertisement(serviceUuids: List<UUID>?, serviceData: Map<UUID, ByteArray>?): Boolean {
        if (serviceUuids?.contains(SERVICE) == true) return true
        val payload = serviceData?.entries?.firstOrNull {
            it.key.toString().equals("00004677-0000-1000-8000-00805f9b34fb", ignoreCase = true)
        }?.value ?: return false
        return payload.size >= 2 &&
            (payload[0].toInt() and 0xff) == 0x77 &&
            (payload[1].toInt() and 0xff) == 0x46
    }

    private fun frame(command: Int, data: ByteArray): ByteArray {
        require(data.size <= 255)
        val packet = ByteArray(data.size + 3)
        packet[0] = command.toByte()
        packet[1] = data.size.toByte()
        data.copyInto(packet, destinationOffset = 2)
        packet[packet.lastIndex] = checksum(packet, packet.lastIndex).toByte()
        return packet
    }

    private fun checksum(bytes: ByteArray, endExclusive: Int): Int {
        var sum = 0
        for (index in 0 until endExclusive) sum = (sum + (bytes[index].toInt() and 0xff)) and 0xff
        return sum
    }
}
