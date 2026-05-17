package com.streamcloud.app.torrent

sealed class TorrentState {
    data object Idle : TorrentState()
    data object Connecting : TorrentState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long = 0,
        val uploadSpeed: Long = 0,
        val peers: Int = 0,
        val seeds: Int = 0,
        val preloadedBytes: Long = 0L,
    ) : TorrentState()

    data class Error(val message: String) : TorrentState()
}
