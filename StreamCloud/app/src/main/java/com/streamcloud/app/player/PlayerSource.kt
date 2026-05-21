package com.streamcloud.app.player

data class PlayerSource(
    val id: String,
    val url: String,

    val label: String,

    val addonName: String,

    val qualityTag: String? = null,
    val isMagnet: Boolean = false,

    val headers: Map<String, String> = emptyMap(),
)
