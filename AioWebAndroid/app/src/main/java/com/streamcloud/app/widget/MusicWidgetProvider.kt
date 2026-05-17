package com.streamcloud.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import com.streamcloud.app.MainActivity
import com.streamcloud.app.R

/**
 * StreamCloud home-screen music widget.
 *
 * Shows the currently playing track title + artist and provides
 * Prev / Play-Pause / Next buttons that fire standard media key events,
 * which Media3 (MusicPlaybackService) handles automatically.
 *
 * Track info is written to SharedPreferences by [updateNowPlaying] — call
 * this from MusicPlaybackService whenever the track or playback state changes.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = buildViews(context)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val PREFS = "music_widget_prefs"
        private const val KEY_TITLE = "track_title"
        private const val KEY_ARTIST = "track_artist"
        private const val KEY_PLAYING = "is_playing"

        fun updateNowPlaying(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putBoolean(KEY_PLAYING, isPlaying)
                .apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                ids.forEach { id -> mgr.updateAppWidget(id, buildViews(context)) }
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString(KEY_TITLE, "StreamCloud") ?: "StreamCloud"
            val artist = prefs.getString(KEY_ARTIST, "Tap to play music") ?: "Tap to play music"
            val isPlaying = prefs.getBoolean(KEY_PLAYING, false)

            val views = RemoteViews(context.packageName, R.layout.widget_music)

            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setTextViewText(R.id.widget_play_pause_btn, if (isPlaying) "⏸" else "▶")

            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_prev_btn, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1))
            views.setOnClickPendingIntent(R.id.widget_play_pause_btn, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 2))
            views.setOnClickPendingIntent(R.id.widget_next_btn, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_NEXT, 3))

            return views
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

        private fun mediaKeyIntent(context: Context, keyCode: Int, requestCode: Int): PendingIntent {
            val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
