package com.streamcloud.app.data.util

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import java.io.File

/**
 * Shared image-loader tuned for music thumbnails.
 *
 * Features
 * ────────
 * • 200 MB persistent disk cache in <cache>/music_thumbnails
 * • Memory cache capped at 20 % of the app's available RAM quota
 * • GIF / animated-WebP decoding support
 * • URL normalisation helpers so different quality variants of the same
 *   YouTube thumbnail share a single on-disk entry
 * • Cache size query + clear helpers (exposed in Settings)
 */
object ThumbnailCache {

    /** Target disk-cache size in bytes (200 MB). */
    const val DISK_CACHE_BYTES = 200L * 1024 * 1024

    /** Subdirectory inside cacheDir used for thumbnail storage. */
    const val CACHE_DIR_NAME = "music_thumbnails"

    @Volatile private var _maxDiskBytes: Long = DISK_CACHE_BYTES
    @Volatile private var _loader: ImageLoader? = null

    /**
     * Returns the singleton [ImageLoader].  Safe to call from any thread;
     * the first caller builds the instance, subsequent callers reuse it.
     */
    fun loader(context: Context): ImageLoader =
        _loader ?: synchronized(this) {
            _loader ?: build(context.applicationContext).also { _loader = it }
        }

    /** Force-rebuilds the loader (e.g. after clearing the cache). */
    fun reset() {
        synchronized(this) { _loader = null }
    }

    /**
     * Updates the maximum disk-cache size and rebuilds the loader on next use.
     * Pass [Long.MAX_VALUE] for "unlimited" (Coil will manage its own eviction).
     */
    fun setMaxDiskBytes(context: Context, bytes: Long) {
        synchronized(this) {
            _maxDiskBytes = bytes
            _loader = null
        }
        loader(context)
    }

    // ── Cache management ──────────────────────────────────────────────────────

    /** Actual bytes currently stored on disk. */
    fun cacheSizeBytes(context: Context): Long =
        cacheDir(context).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    /** Human-readable size string, e.g. "47 MB". */
    fun cacheSizeLabel(context: Context): String {
        val mb = cacheSizeBytes(context) / (1024.0 * 1024.0)
        return if (mb < 1.0) "< 1 MB" else "%.0f MB".format(mb)
    }

    /** Evicts all cached thumbnail data from both memory and disk. */
    fun clear(context: Context) {
        loader(context).run {
            memoryCache?.clear()
            diskCache?.clear()
        }
    }

    fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME)

    // ── URL normalisation ─────────────────────────────────────────────────────

    /**
     * Upgrades a YouTube / YouTube-Music thumbnail URL to the highest
     * quality variant that is broadly available.
     *
     * • ytimg.com video thumbnails: mqdefault → hqdefault (480×360, safe)
     * • YouTube-Music album art (lh3.googleusercontent.com): bumps the
     *   width/height query to 500 px for sharper artwork without risk of 404.
     *
     * Returns [url] unchanged for any other domain.
     */
    fun upgradeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        return when {
            // ytimg video thumbnails ─────────────────────────────────────────
            url.contains("i.ytimg.com") -> url
                .replace("/mqdefault.jpg", "/hqdefault.jpg")
                .replace("/sddefault.jpg", "/hqdefault.jpg")
                .replace("/default.jpg",   "/hqdefault.jpg")

            // YouTube Music album-art ────────────────────────────────────────
            // Typical suffix: =w226-h226-l90-rj  →  =w500-h500-l90-rj
            url.contains("lh3.googleusercontent.com") -> url
                .replace(Regex("=w\\d+-h\\d+"), "=w500-h500")

            else -> url
        }
    }

    /**
     * Returns a stable cache-key string for a thumbnail URL so that
     * different quality variants of the same video/album don't produce
     * separate cache entries.
     *
     * Strip resolution suffixes so w226 and w500 share one key.
     */
    fun cacheKey(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url
            .replace(Regex("=w\\d+-h\\d+(-[^?]+)?"), "=w500-h500-l90-rj")
            .replace(Regex("/(mq|hq|sd|maxres)?default\\.jpg"), "/hqdefault.jpg")
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.20)   // 20 % of app RAM quota
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir(context))
                .maxSizeBytes(_maxDiskBytes)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()
}
