package com.streamcloud.app.data.livetv

enum class SourceType { M3U_URL, XTREAM, SINGLE }

/** A user-added IPTV source (M3U playlist, Xtream credentials, or a single stream URL). */
data class LiveTvSource(
    val id: String,
    val name: String,
    val type: SourceType,
    // M3U_URL + SINGLE
    val url: String = "",
    // XTREAM
    val xtreamServer: String = "",
    val xtreamUser: String   = "",
    val xtreamPass: String   = "",
    // Optional XMLTV EPG URL (M3U sources)
    val epgUrl: String = "",
)

/** A single playable live-TV channel. */
data class LiveTvChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String  = "",
    val group: String = "General",
    val epgId: String = "",
    val sourceId: String = "",
    val currentProgram: String = "",
    val nextProgram: String    = "",
)
