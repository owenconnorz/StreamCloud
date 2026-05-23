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
import android.widget.RemoteViews
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R
import java.net.HttpURLConnection
import java.net.URL

class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Refresh from stored prefs whenever the launcher requests an update.
        Thread {
            val views = buildViews(context)
            appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
        }.start()
    }

    companion object {
        private const val PREFS            = "music_widget_prefs"
        private const val KEY_TITLE        = "track_title"
        private const val KEY_ARTIST       = "track_artist"
        private const val KEY_ARTWORK_URL  = "artwork_url"
        private const val KEY_RECENT       = "recent_url_"   // + index 0..4

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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putString(KEY_ARTWORK_URL, artworkUrl)
            for (i in 0..4) {
                prefs.putString("$KEY_RECENT$i", recentUrls.getOrNull(i) ?: "")
            }
            prefs.apply()
            refreshAllWidgets(context)
        }

        // ── Internal helpers ─────────────────────────────────────────────────

        private fun refreshAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            Thread {
                val views = buildViews(context)
                ids.forEach { id -> mgr.updateAppWidget(id, views) }
            }.start()
        }

        private fun buildViews(context: Context): RemoteViews {
            val prefs  = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title  = prefs.getString(KEY_TITLE, "StreamCloud") ?: "StreamCloud"
            val artist = prefs.getString(KEY_ARTIST, "Not playing") ?: "Not playing"
            val artUrl = prefs.getString(KEY_ARTWORK_URL, "") ?: ""

            val views = RemoteViews(context.packageName, R.layout.widget_music)

            // Text
            views.setTextViewText(R.id.widget_title,  title)
            views.setTextViewText(R.id.widget_artist, artist)

            // App logo — use the launcher icon
            views.setImageViewResource(R.id.widget_logo, R.mipmap.ic_launcher)

            // Album art
            val artBmp = if (artUrl.isNotBlank()) downloadSquareBitmap(artUrl, 128) else null
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
                val url = prefs.getString("$KEY_RECENT$i", "") ?: ""
                val bmp = if (url.isNotBlank()) downloadSquareBitmap(url, 96) else null
                if (bmp != null) {
                    views.setImageViewBitmap(recentViewIds[i], roundCrop(bmp, 0.12f))
                }
                // If null, the widget_thumb_bg drawable placeholder remains visible
            }

            // Tap the whole widget → open app
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

            return views
        }

        /**
         * Downloads a bitmap from [url], centre-crops it to a square, then
         * scales it to [sizePx] × [sizePx]. Returns null on any error.
         */
        private fun downloadSquareBitmap(url: String, sizePx: Int): Bitmap? = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout    = 10_000
            conn.instanceFollowRedirects = true
            val src = BitmapFactory.decodeStream(conn.inputStream)
            conn.disconnect()
            if (src == null) null
            else {
                // Centre-crop to square first
                val side = minOf(src.width, src.height)
                val x    = (src.width  - side) / 2
                val y    = (src.height - side) / 2
                val square = Bitmap.createBitmap(src, x, y, side, side)
                Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
            }
        } catch (_: Exception) { null }

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
