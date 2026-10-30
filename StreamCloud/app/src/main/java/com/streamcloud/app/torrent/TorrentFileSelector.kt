package com.streamcloud.app.torrent

import com.nuvio.engine.NuvioTorrentFile

internal object TorrentFileSelector {
    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv",
    )

    fun select(
        files: List<NuvioTorrentFile>,
        requestedIndex: Int?,
        filename: String?,
    ): Int? {
        if (files.isEmpty()) return requestedIndex

        val cleanName = filename?.trim()?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
        if (cleanName != null) {
            files.firstOrNull {
                it.path.substringAfterLast('/').equals(cleanName, ignoreCase = true)
            }?.let { return it.index }
            files.firstOrNull { it.path.contains(cleanName, ignoreCase = true) }
                ?.let { return it.index }
        }

        if (requestedIndex != null && files.any { it.index == requestedIndex }) {
            return requestedIndex
        }

        val video = files
            .filter { it.path.substringAfterLast('.', "").lowercase() in videoExtensions }
            .maxByOrNull(NuvioTorrentFile::size)
        return (video ?: files.maxByOrNull(NuvioTorrentFile::size))?.index
    }
}
