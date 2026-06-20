package com.streamcloud.app.data.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal object PluginUpdateNotifier {

    private const val CHANNEL_ID   = "plugin_updates"
    private const val CHANNEL_NAME = "Plugin & Addon Updates"

    data class UpdatedItem(val type: String, val name: String)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when Stremio addons, Nuvio providers or CloudStream plugins receive updates"
            },
        )
    }

    fun notify(context: Context, updates: List<UpdatedItem>) {
        if (updates.isEmpty()) return
        runCatching {
            ensureChannel(context)
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

            updates.groupBy { it.type }.forEach { (type, items) ->
                val plural  = if (items.size > 1) "s" else ""
                val title   = when (type) {
                    "Stremio"     -> "Stremio Addon$plural Updated"
                    "Nuvio"       -> "Nuvio Provider$plural Updated"
                    "CloudStream" -> "CloudStream Plugin$plural Updated"
                    else          -> "$type Updated"
                }
                val body = if (items.size == 1) {
                    items[0].name
                } else {
                    "${items.size} updated: ${items.joinToString(", ") { it.name }}"
                }

                val notifId = (type + "_updates").hashCode() and 0x7fffffff
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                nm.notify(notifId, builder.build())
            }
        }
    }
}
