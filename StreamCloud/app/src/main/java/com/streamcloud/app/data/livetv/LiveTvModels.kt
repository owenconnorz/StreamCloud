package com.streamcloud.app.data.livetv

enum class SourceType { M3U_URL, XTREAM, SINGLE }

/** A user-added IPTV source (M3U playlist, Xtream credentials, or a single stream URL). */
data class LiveTvSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String = "",
    val xtreamServer: String = "",
    val xtreamUser: String   = "",
    val xtreamPass: String   = "",
    val epgUrl: String = "",
)

/** A single playable live-TV channel. */
data class LiveTvChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String     = "",
    val group: String    = "General",
    val epgId: String    = "",
    val language: String = "",   // tvg-language attribute, e.g. "English", "Arabic"
    val sourceId: String = "",
    val currentProgram: String = "",
    val nextProgram: String    = "",
    /** null = not yet probed, true = stream responded OK, false = stream dead/unreachable */
    val isAlive: Boolean? = null,
)
