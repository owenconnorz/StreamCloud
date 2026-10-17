package com.streamcloud.app.data.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartyMedia(
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("media_type") val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val title: String,
) {
    val key: String get() = "$mediaType:$tmdbId:$season:$episode"
}

@Serializable
data class PartyMember(
    val id: String,
    val name: String,
    val connected: Boolean = false,
    val status: String = "browsing",
)

@Serializable
data class PartyPlayback(
    @SerialName("position_ms") val positionMs: Long = 0,
    val paused: Boolean = true,
    val rate: Float = 1f,
    val at: Long = 0,
    @SerialName("member_id") val memberId: String? = null,
)

@Serializable
data class PartyRoom(
    val code: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_only") val hostOnly: Boolean = true,
    val media: PartyMedia? = null,
    val seq: Long = 0,
    val playback: PartyPlayback = PartyPlayback(),
    val members: List<PartyMember> = emptyList(),
    @SerialName("max_members") val maxMembers: Int = 12,
    @SerialName("server_time") val serverTime: Long = 0,
)

data class PartyState(
    val room: PartyRoom? = null,
    val memberId: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isHost: Boolean get() = room != null && room.hostId == memberId
    val canControl: Boolean get() = connected && (isHost || room?.hostOnly == false)
}

