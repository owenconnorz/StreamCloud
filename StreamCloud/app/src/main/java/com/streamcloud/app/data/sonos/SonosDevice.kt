package com.streamcloud.app.data.sonos

/**
 * A discovered Sonos zone player on the local network.
 *
 * [udn] is the UUID that uniquely identifies the physical device — used as
 * the stable key in the device list so duplicate SSDP responses are deduped.
 * [host] is the LAN IP (e.g. "192.168.1.42") and [port] is always 1400 for
 * Sonos UPnP control. [name] is the user-visible room label ("Living Room").
 */
data class SonosDevice(
    val udn: String,
    val name: String,
    val host: String,
    val port: Int = 1400,
)
