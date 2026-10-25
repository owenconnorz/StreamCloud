package com.streamcloud.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class MusicProviderManifest(
    val id: String,
    val name: String,
    val version: Int,
    val description: String,
    val adapter: String,
)

/**
 * Reads credential-free, declarative definitions from the dedicated public repository.
 * Definitions cannot contain code, API URLs, or credential destinations.
 */
object MusicProviderCatalogRepository {
    private const val ROOT =
        "https://raw.githubusercontent.com/owenconnorz/StreamCloud-Music-Providers/main/"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): List<MusicProviderManifest> = withContext(Dispatchers.IO) {
        val catalog = JSONObject(get(ROOT + "catalog.json"))
        require(catalog.optInt("schemaVersion") == 1) { "Unsupported provider catalogue version." }
        val entries = catalog.optJSONArray("providers")
            ?: error("Provider catalogue is missing its provider list.")
        require(entries.length() <= 40) { "Provider catalogue is larger than supported." }
        (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val id = entry.getString("id")
            require(id.matches(Regex("[a-z0-9_-]{1,40}"))) { "Invalid provider ID." }
            val path = entry.getString("path")
            require(path == "providers/$id.json") { "Invalid provider definition path." }
            val expectedHash = entry.getString("sha256")
            require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider definition hash." }
            val definition = get(ROOT + path)
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(definition.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            require(actualHash == expectedHash) { "Provider definition checksum mismatch." }
            val item = JSONObject(definition)
            require(item.optInt("schemaVersion") == 1 && item.optString("id") == id) {
                "Provider definition does not match the catalogue."
            }
            require(item.optInt("version") == entry.optInt("version") && item.optInt("version") > 0) {
                "Provider version does not match the catalogue."
            }
            // Only app-shipped adapters are executable. Unknown IDs or adapters are rejected.
            require(id == "spotify" && item.optString("adapter") == "spotify-playlists-v1") {
                "This provider adapter is not supported by this StreamCloud version."
            }
            val capabilities = item.optJSONArray("capabilities")
                ?: error("Provider capabilities are missing.")
            require(capabilities.length() == 1 && capabilities.optString(0) == "playlists") {
                "Unsupported Spotify capabilities."
            }
            require(item.optString("playback") == "youtube-match") {
                "Unsupported Spotify playback mode."
            }
            require(
                item.keys().asSequence().toSet() == setOf(
                    "schemaVersion", "id", "version", "name", "adapter",
                    "description", "capabilities", "playback",
                ),
            ) { "Provider definition contains unsupported fields." }
            val description = item.optString("description")
            require(
                description.isNotBlank() && description.length <= 240 &&
                    description.none(Char::isISOControl),
            ) { "Invalid provider description." }
            require(entry.optString("name") == item.optString("name")) {
                "Provider name does not match the catalogue."
            }
            MusicProviderManifest(
                id = id,
                name = item.getString("name"),
                version = item.getInt("version"),
                description = description,
                adapter = item.getString("adapter"),
            )
        }.distinctBy { it.id }.also {
            require(it.size == entries.length()) { "Provider catalogue contains duplicate IDs." }
        }
    }

    private fun get(url: String): String {
        val response = client.newCall(
            Request.Builder().url(url).header("Accept", "application/json").build(),
        ).execute()
        response.use {
            require(it.isSuccessful) { "Provider catalogue request failed (HTTP ${it.code})." }
            return it.body?.string()?.takeIf(String::isNotBlank)
                ?: error("Provider catalogue returned an empty response.")
        }
    }
}
