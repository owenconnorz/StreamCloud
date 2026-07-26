package com.streamcloud.app.cast.dlna

data class DlnaDevice(
    val udn: String,
    val name: String,
    val host: String,
    val port: Int,
    val avTransportControlPath: String,
) {
    val avTransportControlUrl: String
        get() = "http://$host:$port$avTransportControlPath"
}
