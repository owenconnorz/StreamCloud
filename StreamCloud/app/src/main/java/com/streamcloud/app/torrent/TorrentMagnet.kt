package com.streamcloud.app.torrent

import java.net.URLDecoder
import java.net.URLEncoder

internal data class PreparedMagnet(
    val uri: String,
    val fileIndexHint: Int? = null,
)

internal object TorrentMagnet {
    private val defaultTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.bittor.pw:1337/announce",
    )
    private val fileIndexPattern = Regex("[?&]_sc_fidx=(\\d+)", RegexOption.IGNORE_CASE)
    private val hex40 = Regex("[0-9a-fA-F]{40}")
    private val hex64 = Regex("[0-9a-fA-F]{64}")
    private val base32Hash = Regex("[A-Z2-7]{32}", RegexOption.IGNORE_CASE)

    fun fromInfoHash(infoHash: String, trackers: List<String>): PreparedMagnet {
        val rawHash = infoHash.trim()
        val v2Topic = when {
            rawHash.startsWith("urn:btmh:", ignoreCase = true) -> {
                val multihash = rawHash.substringAfter(':').substringAfter(':')
                if (multihash.matches(Regex("1220[0-9a-fA-F]{64}"))) {
                    "urn:btmh:$multihash"
                } else {
                    null
                }
            }
            else -> null
        }
        val hash = when {
            rawHash.startsWith("urn:btih:", ignoreCase = true) ->
                rawHash.substringAfter(':').substringAfter(':')
            rawHash.startsWith("urn:btmh:", ignoreCase = true) ->
                rawHash.substringAfter(':').substringAfter(':').removePrefix("1220")
            else -> rawHash
        }
        val topic = when {
            v2Topic != null -> v2Topic
            hex40.matches(hash) || base32Hash.matches(hash) -> "urn:btih:$hash"
            hex64.matches(hash) -> "urn:btmh:1220$hash"
            else -> throw TorrentException("Invalid torrent info hash")
        }
        val magnet = "magnet:?xt=$topic"
        return addTrackers(magnet, trackers)
    }

    fun parse(magnet: String): PreparedMagnet {
        val index = fileIndexPattern.find(magnet)?.groupValues?.get(1)?.toIntOrNull()
        var clean = fileIndexPattern.replace(magnet, "")
            .replace("?&", "?")
            .trimEnd('?', '&')
        if (!clean.startsWith("magnet:?", ignoreCase = true)) {
            throw TorrentException("Expected a magnet link")
        }

        val topics = queryValues(clean, "xt")
        val hasSupportedTopic = topics.any {
            it.startsWith("urn:btih:", ignoreCase = true) ||
                it.startsWith("urn:btmh:", ignoreCase = true)
        }
        if (!hasSupportedTopic) {
            throw TorrentException("Magnet link does not contain a supported torrent hash")
        }
        clean = appendDefaultTrackers(clean)
        return PreparedMagnet(clean, index)
    }

    private fun addTrackers(magnet: String, extraTrackers: List<String>): PreparedMagnet {
        val allTrackers = (defaultTrackers + extraTrackers)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val uri = buildString {
            append(magnet)
            allTrackers.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        return PreparedMagnet(uri)
    }

    private fun appendDefaultTrackers(magnet: String): String {
        val existing = queryValues(magnet, "tr").toSet()
        val missing = defaultTrackers.filterNot(existing::contains)
        if (missing.isEmpty()) return magnet
        return buildString {
            append(magnet)
            missing.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        }
    }

    private fun queryValues(uri: String, name: String): List<String> =
        uri.substringAfter('?', "")
            .split('&')
            .mapNotNull { parameter ->
                val key = parameter.substringBefore('=')
                if (!key.equals(name, ignoreCase = true)) return@mapNotNull null
                runCatching {
                    URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
                }.getOrDefault(parameter.substringAfter('=', ""))
            }
}
