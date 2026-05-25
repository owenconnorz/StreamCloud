package com.streamcloud.app.data.sonos

data class SonosDevice(
    val udn: String,
    val name: String,
    val host: String,
    val port: Int = 1400,
)

data class SonosGroup(
    val id: String,
    val name: String,
    val coordinator: SonosDevice,
    val members: List<SonosDevice>,
)
