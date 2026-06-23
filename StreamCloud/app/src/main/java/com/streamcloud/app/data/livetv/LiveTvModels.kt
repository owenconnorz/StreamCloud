package com.streamcloud.app.data.livetv

enum class SourceType { M3U_URL, XTREAM, SINGLE }

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

data class LiveTvChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String     = "",
    val group: String    = "General",
    val epgId: String    = "",
    val language: String = "",
    val chno: Int        = 0,   // tvg-chno from M3U, 0 = not set
    val sourceId: String = "",
    val currentProgram: String = "",
    val nextProgram: String    = "",
    val isAlive: Boolean? = null,
)
