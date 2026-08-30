package com.streamcloud.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import android.widget.RemoteViews
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Launcher refreshes must finish inside the receiver lifetime. Render immediately from
        // preferences and the widget's disk cache; network refreshes are pushed by playback.
        val appContext = context.applicationContext
        val version = updateVersion.get()
        val views = buildViews(
            context = appContext,
            allowNetwork = false,
            expectedVersion = version,
        ) ?: return
        if (version != updateVersion.get()) return
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    companion object {
        private const val PREFS            = "music_widget_prefs"
        private const val KEY_TITLE        = "track_title"
        private const val KEY_ARTIST       = "track_artist"
        private const val KEY_ARTWORK_URL  = "artwork_url"
        private const val KEY_RECENT       = "recent_url_"   // + index 0..4
        private val renderExecutor = Executors.newSingleThreadExecutor()
        private val updateVersion = AtomicLong(0)
        private val preferencesLock = Any()
        private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val trustedArtworkHosts = setOf(
            "ytimg.com",
            "googleusercontent.com",
            "ggpht.com",
            "scdn.co",
            "spotifycdn.com",
        )
        private data class WidgetState(
            val title: String,
            val artist: String,
            val artworkUrl: String,
            val recentUrls: List<String>,
        )

        // ── Public API ────────────────────────────────────────────────────────

        /**
         * Called by MusicPlaybackService whenever the current track changes.
         * Persists metadata to SharedPreferences and triggers a widget redraw
         * on a background thread (bitmap downloads must not block the main thread).
         */
        fun updateNowPlaying(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String,
            recentUrls: List<String>,
        ) {
            val appContext = context.applicationContext
            val version = synchronized(preferencesLock) {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TITLE, title)
                    .putString(KEY_ARTIST, artist)
                    .putString(KEY_ARTWORK_URL, artworkUrl)
                for (i in 0..4) {
                    prefs.putString("$KEY_RECENT$i", recentUrls.getOrNull(i) ?: "")
                }
                prefs.apply()
                updateVersion.incrementAndGet()
            }
            refreshAllWidgets(appContext, version)
        }

        // ── Internal helpers ─────────────────────────────────────────────────

        private fun refreshAllWidgets(context: Context, version: Long) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            enqueueRender(context.applicationContext, ids, version)
        }

        /**
         * Bitmap I/O is serialized. Checking the version after it completes prevents a slow,
         * stale render from replacing the current track's RemoteViews.
         */
        private fun enqueueRender(context: Context, ids: IntArray, version: Long) {
            renderExecutor.execute {
                if (version != updateVersion.get()) return@execute
                val views = buildViews(
                    context = context,
                    allowNetwork = true,
                    expectedVersion = version,
                ) ?: return@execute
                if (version != updateVersion.get()) return@execute
                val manager = AppWidgetManager.getInstance(context)
                ids.forEach { id -> manager.updateAppWidget(id, views) }
            }
        }

        private fun buildViews(
            context: Context,
            allowNetwork: Boolean,
            expectedVersion: Long,
        ): RemoteViews? {
            if (expectedVersion != updateVersion.get()) return null
            val state = synchronized(preferencesLock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                WidgetState(
                    title = prefs.getString(KEY_TITLE, "StreamCloud") ?: "StreamCloud",
                    artist = prefs.getString(KEY_ARTIST, "Not playing") ?: "Not playing",
                    artworkUrl = prefs.getString(KEY_ARTWORK_URL, "") ?: "",
                    recentUrls = (0..4).map { prefs.getString("$KEY_RECENT$it", "") ?: "" },
                )
            }

            val views = RemoteViews(context.packageName, R.layout.widget_music)

            // Text
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_artist, state.artist)

            // App logo — use the launcher icon
            views.setImageViewResource(R.id.widget_logo, R.mipmap.ic_launcher)

            // Album art
            val artBmp = loadArtworkBitmap(
                context = context,
                url = state.artworkUrl,
                sizePx = 128,
                allowNetwork = allowNetwork,
                expectedVersion = expectedVersion,
            )
            if (artBmp != null) {
                views.setImageViewBitmap(R.id.widget_art, roundCrop(artBmp, 0.12f))
            } else {
                views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
            }

            // Recent thumbnails
            val recentViewIds = listOf(
                R.id.widget_recent_1, R.id.widget_recent_2, R.id.widget_recent_3,
                R.id.widget_recent_4, R.id.widget_recent_5,
            )
            for (i in 0..4) {
                val url = state.recentUrls[i]
                val bmp = loadArtworkBitmap(
                    context = context,
                    url = url,
                    sizePx = 96,
                    allowNetwork = allowNetwork,
                    expectedVersion = expectedVersion,
                )
                if (bmp != null) {
                    views.setImageViewBitmap(recentViewIds[i], roundCrop(bmp, 0.12f))
                } else {
                    // RemoteViews can retain a previous image; explicitly render the app icon
                    // for empty/missing history instead of leaving a blank grey tile.
                    views.setImageViewResource(recentViewIds[i], R.mipmap.ic_launcher)
                }
            }

            // Tap the whole widget → open app
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

            return views.takeIf { expectedVersion == updateVersion.get() }
        }

        private fun loadArtworkBitmap(
            context: Context,
            url: String,
            sizePx: Int,
            allowNetwork: Boolean,
            expectedVersion: Long,
        ): Bitmap? {
            if (url.isBlank() || !isTrustedArtworkUrl(url)) return null
            if (expectedVersion != updateVersion.get()) return null

            val cacheKey = "$sizePx:$url"
            bitmapCache.get(cacheKey)?.let { return it }

            val cacheDir = File(context.cacheDir, "music_widget").apply { mkdirs() }
            val cacheFile = File(cacheDir, "${Integer.toHexString(cacheKey.hashCode())}.png")
            if (cacheFile.isFile) {
                BitmapFactory.decodeFile(cacheFile.path)?.let { cached ->
                    bitmapCache.put(cacheKey, cached)
                    return cached
                }
            }

            if (!allowNetwork || expectedVersion != updateVersion.get()) return null
            val downloaded = downloadSquareBitmap(url, sizePx) ?: return null
            bitmapCache.put(cacheKey, downloaded)
            runCatching {
                cacheFile.outputStream().use { output ->
                    downloaded.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                cacheDir.listFiles()
                    ?.sortedByDescending(File::lastModified)
                    ?.drop(40)
                    ?.forEach(File::delete)
            }
            return downloaded.takeIf { expectedVersion == updateVersion.get() }
        }

        private fun isTrustedArtworkUrl(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
            return trustedArtworkHosts.any { trusted ->
                host == trusted || host.endsWith(".$trusted")
            }
        }

        /**
         * Downloads a bitmap from [url], centre-crops it to a square, then
         * scales it to [sizePx] × [sizePx]. Returns null on any error.
         */
        private fun downloadSquareBitmap(url: String, sizePx: Int): Bitmap? {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 5_000
                conn.instanceFollowRedirects = false
                if (conn.responseCode !in 200..299) return null
                val src = conn.inputStream.use { input -> BitmapFactory.decodeStream(input) }
                    ?: return null
                val side = minOf(src.width, src.height)
                val x = (src.width - side) / 2
                val y = (src.height - side) / 2
                val square = Bitmap.createBitmap(src, x, y, side, side)
                Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

        /**
         * Clips [src] to a rounded rectangle.
         * [radiusFraction] is the corner radius as a fraction of bitmap width (0–1).
         */
        private fun roundCrop(src: Bitmap, radiusFraction: Float): Bitmap {
            val out    = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect   = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            val radius  = src.width * radiusFraction
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(src, 0f, 0f, paint)
            return out
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
