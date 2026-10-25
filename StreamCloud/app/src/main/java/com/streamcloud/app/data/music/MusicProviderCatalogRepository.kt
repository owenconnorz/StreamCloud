package com.streamcloud.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
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
    private const val GITHUB_HOST = "github.com"
    private const val RAW_HOST = "raw.githubusercontent.com"
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeRepositoryUrl(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.port in listOf(-1, 443)
        ) { "Use an HTTPS GitHub repository link." }
        val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        val ownerRepo = Regex("[A-Za-z0-9_.-]{1,100}")
        return when (uri.host?.lowercase()) {
            GITHUB_HOST -> {
                require(parts.size == 2 && parts.all(ownerRepo::matches) &&
                    parts.none { it == "." || it == ".." }
                ) {
                    "Paste a GitHub repository link such as https://github.com/owner/repository."
                }
                "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/catalog.json"
            }
            RAW_HOST -> {
                require(parts.size == 4 && parts[3] == "catalog.json" &&
                    parts[0].matches(ownerRepo) && parts[1].matches(ownerRepo) &&
                    parts[0] !in setOf(".", "..") && parts[1] !in setOf(".", "..") &&
                    parts[2].matches(Regex("[A-Za-z0-9_.-]{1,120}"))
                    && parts[2] !in setOf(".", "..")
                ) { "Paste a raw GitHub catalog.json link." }
                "https://raw.githubusercontent.com/${parts.joinToString("/")}"
            }
            else -> error("Only GitHub repository links are supported.")
        }
    }

    suspend fun fetch(repositoryUrl: String): List<MusicProviderManifest> = withContext(Dispatchers.IO) {
        val catalogUrl = normalizeRepositoryUrl(repositoryUrl)
        val root = catalogUrl.removeSuffix("catalog.json")
        val catalog = JSONObject(get(catalogUrl))
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
            val definition = get(root + path)
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
        val requestUrl = URI(url)
        require(requestUrl.scheme == "https" && requestUrl.host == RAW_HOST) {
            "Provider content must use secure GitHub raw links."
        }
        val response = client.newCall(
            Request.Builder().url(url).header("Accept", "application/json").build(),
        ).execute()
        response.use {
            require(it.isSuccessful && URI(it.request.url.toString()).host == RAW_HOST) {
                "Provider catalogue request failed (HTTP ${it.code})."
            }
            return it.body?.string()?.takeIf(String::isNotBlank)
                ?: error("Provider catalogue returned an empty response.")
        }
    }
}
