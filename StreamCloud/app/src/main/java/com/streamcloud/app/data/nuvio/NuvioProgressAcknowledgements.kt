package com.streamcloud.app.data.nuvio

import android.content.Context
import com.streamcloud.app.data.library.WatchProgressEntity

/**
 * Equivalent to Nuvio's pending progress upserts: pulls are not local edits.
 * Acknowledging the exact sent snapshot leaves edits made during an RPC pending.
 */
internal class NuvioProgressAcknowledgements(
    context: Context,
    userId: String,
    localProfileId: String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "nuvio_progress_ack_v1", Context.MODE_PRIVATE,
    )
    private val scope = "${stableKey(userId)}:${stableKey(localProfileId)}"

    private fun key(entry: WatchProgressEntity): String =
        "$scope:${entry.mediaType}:${entry.tmdbId}:${entry.seasonNumber}:${entry.episodeNumber}"

    private fun fingerprint(entry: WatchProgressEntity): String =
        nuvioProgressFingerprint(entry.positionMs, entry.durationMs, entry.updatedAt)

    fun isPending(entry: WatchProgressEntity): Boolean =
        isPendingNuvioProgress(fingerprint(entry), prefs.getString(key(entry), null))

    fun hasPendingLocalEdit(entry: WatchProgressEntity): Boolean {
        val acknowledged = prefs.getString(key(entry), null) ?: return false
        return isPendingNuvioProgress(fingerprint(entry), acknowledged)
    }

    fun acknowledge(entries: Collection<WatchProgressEntity>) {
        if (entries.isEmpty()) return
        val editor = prefs.edit()
        entries.forEach { editor.putString(key(it), fingerprint(it)) }
        check(editor.commit()) { "Could not acknowledge Nuvio playback progress" }
    }
}
