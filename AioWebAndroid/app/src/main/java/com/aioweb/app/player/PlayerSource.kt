package com.aioweb.app.player

/**
 * Lightweight stream descriptor surfaced inside the player UI.
 * Used by the bottom-sheet "Sources" picker to switch between Stremio
 * addon streams without leaving the player.
 */
data class PlayerSource(
    val id: String,
    val url: String,
    /** What the user sees as the row label. Usually the stream's `title`. */
    val label: String,
    /** Addon brand chip (e.g. "Torrentio", "NuvioStreams"). */
    val addonName: String,
    /** "1080p", "4K", "HDR" etc — used to sort. */
    val qualityTag: String? = null,
    val isMagnet: Boolean = false,
    /** Optional HTTP headers (Referer / Origin / cookies) — CloudStream extractors
     *  routinely require these; without them the upstream returns 403/redirect. */
    val headers: Map<String, String> = emptyMap(),
)
