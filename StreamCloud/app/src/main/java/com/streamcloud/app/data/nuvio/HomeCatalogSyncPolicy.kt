package com.streamcloud.app.data.nuvio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object HomeCatalogSyncPolicy {
    fun canonicalSnapshot(snapshot: JsonObject): String =
        canonicalize(snapshot).toString()

    fun shouldApplyRemoteSnapshot(
        localSnapshot: JsonObject,
        lastAcknowledgedSnapshot: String?,
        hasPendingLocalChanges: Boolean,
        hasLegacyExplicitLocalValues: Boolean,
    ): Boolean {
        if (hasPendingLocalChanges) return false

        val localCanonical = canonicalSnapshot(localSnapshot)
        return if (lastAcknowledgedSnapshot == null) {
            !hasLegacyExplicitLocalValues
        } else {
            localCanonical == lastAcknowledgedSnapshot
        }
    }

    fun isSentSnapshotStillCurrent(
        currentLocalSnapshot: JsonObject,
        sentSnapshot: JsonObject,
    ): Boolean =
        canonicalSnapshot(currentLocalSnapshot) == canonicalSnapshot(sentSnapshot)

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries
                .sortedBy { it.key }
                .associate { (key, element) -> key to canonicalize(element) },
        )
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }
}