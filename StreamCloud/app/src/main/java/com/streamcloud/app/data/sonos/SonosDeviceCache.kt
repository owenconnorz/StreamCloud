package com.streamcloud.app.data.sonos

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SonosDeviceCache {
    private const val MAX_CACHED_DEVICES = 64

    internal data class Snapshot(
        val devices: List<SonosDevice>,
        val groups: List<SonosGroup>,
    )

    @Serializable
    private data class StoredDevice(
        val udn: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    @Serializable
    private data class StoredGroup(
        val id: String,
        val coordinatorUdn: String,
        val coordinatorHost: String,
        val coordinatorPort: Int,
        val memberNames: List<String>,
    )

    @Serializable
    private data class StoredSnapshot(
        val version: Int = 1,
        val devices: List<StoredDevice>,
        val groups: List<StoredGroup>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(devices: List<SonosDevice>, groups: List<SonosGroup>): String =
        json.encodeToString(
            StoredSnapshot(
                devices = devices.asSequence()
                    .filter(::isUsable)
                    .distinctBy { it.udn }
                    .take(MAX_CACHED_DEVICES)
                    .map { StoredDevice(it.udn, it.name, it.host, it.port) }
                    .toList(),
                groups = groups.asSequence()
                    .filter(::isUsable)
                    .distinctBy { it.id }
                    .take(MAX_CACHED_DEVICES)
                    .map {
                        StoredGroup(
                            id = it.id,
                            coordinatorUdn = it.coordinatorUdn,
                            coordinatorHost = it.coordinatorHost,
                            coordinatorPort = it.coordinatorPort,
                            memberNames = it.memberNames,
                        )
                    }
                    .toList(),
            ),
        )

    fun decode(value: String?): Snapshot? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<StoredSnapshot>(value)
                .takeIf { it.version == 1 }
                ?.let { stored ->
                    Snapshot(
                        devices = stored.devices.asSequence()
                            .map { SonosDevice(it.udn, it.name, it.host, it.port) }
                            .filter(::isUsable)
                            .distinctBy { it.udn }
                            .take(MAX_CACHED_DEVICES)
                            .toList(),
                        groups = stored.groups.asSequence()
                            .map {
                                SonosGroup(
                                    id = it.id,
                                    coordinatorUdn = it.coordinatorUdn,
                                    coordinatorHost = it.coordinatorHost,
                                    coordinatorPort = it.coordinatorPort,
                                    memberNames = it.memberNames,
                                )
                            }
                            .filter(::isUsable)
                            .distinctBy { it.id }
                            .take(MAX_CACHED_DEVICES)
                            .toList(),
                    )
                }
        }.getOrNull()
    }

    fun decodeLegacyDevices(value: String?): List<SonosDevice> {
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
            isIpv4(device.host)

    private fun isUsable(group: SonosGroup): Boolean =
        group.id.isNotBlank() &&
            group.id.length <= 256 &&
            group.coordinatorUdn.isNotBlank() &&
            group.coordinatorUdn.length <= 256 &&
            group.coordinatorPort in 1..65535 &&
            isIpv4(group.coordinatorHost) &&
            group.memberNames.isNotEmpty() &&
            group.memberNames.size <= MAX_CACHED_DEVICES &&
            group.memberNames.all { it.isNotBlank() && it.length <= 128 }

    private fun isIpv4(host: String): Boolean =
        host.split('.').let { octets ->
            octets.size == 4 && octets.all { octet ->
                val value = octet.toIntOrNull()
                value != null && value in 0..255
            }
        }
}
