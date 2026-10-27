package com.streamcloud.app.data.sonos

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SonosDeviceCache {
    private const val MAX_CACHED_DEVICES = 64

    @Serializable
    private data class StoredDevice(
        val udn: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(devices: List<SonosDevice>): String =
        json.encodeToString(
            devices.asSequence()
                .filter(::isUsable)
                .distinctBy { it.udn }
                .take(MAX_CACHED_DEVICES)
                .map { StoredDevice(it.udn, it.name, it.host, it.port) }
                .toList(),
        )

    fun decode(value: String?): List<SonosDevice> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredDevice>>(value)
                .asSequence()
                .map { SonosDevice(it.udn, it.name, it.host, it.port) }
                .filter(::isUsable)
                .distinctBy { it.udn }
                .take(MAX_CACHED_DEVICES)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun isUsable(device: SonosDevice): Boolean =
        device.udn.isNotBlank() &&
            device.udn.length <= 256 &&
            device.name.isNotBlank() &&
            device.name.length <= 128 &&
            device.port in 1..65535 &&
            device.host.split('.').let { octets ->
                octets.size == 4 && octets.all { octet ->
                    val value = octet.toIntOrNull()
                    value != null && value in 0..255
                }
            }
}
