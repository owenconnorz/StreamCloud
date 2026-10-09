package com.streamcloud.app.data.ytmusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Fail-closed detection for Premium entitlement in YouTube Music account-menu data. */
internal object YtMusicPremiumDetector {
    private val entitlementBooleanKeys = setOf(
        "isPremiumSubscriber",
        "isPremium",
        "isPremiumUser",
        "hasPremium",
        "hasPremiumSubscription",
        "isPremiumMember",
        "premiumMembershipActive",
    )
    private val activeHeaderStatusKeys = setOf(
        "accountByline",
        "accountBylineRenderer",
        "membershipBadge",
        "premiumBadge",
        "accountBadge",
        "badge",
        "iconType",
        "accessibilityLabel",
    )
    private val premiumStatusLabels = setOf(
        "premium",
        "youtube premium",
        "youtube premium member",
        "youtube premium subscriber",
        "premium member",
        "premium subscriber",
    )
    private val premiumStatusIcons = setOf("YOUTUBE_PREMIUM", "PREMIUM")

    fun isPremium(response: JsonElement): Boolean =
        hasExplicitEntitlement(response) || activeAccountHeaders(response).any(::headerConfirmsPremium)

    private fun hasExplicitEntitlement(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            (key in entitlementBooleanKeys && value.jsonPrimitiveOrNull()?.booleanOrNull == true) ||
                hasExplicitEntitlement(value)
        }
        is JsonArray -> element.any(::hasExplicitEntitlement)
        else -> false
    }

    private fun activeAccountHeaders(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> buildList {
            element.forEach { (key, value) ->
                if (key == "activeAccountHeaderRenderer" && value is JsonObject) add(value)
                addAll(activeAccountHeaders(value))
            }
        }
        is JsonArray -> element.flatMap(::activeAccountHeaders)
        else -> emptyList()
    }

    private fun headerConfirmsPremium(header: JsonObject): Boolean =
        hasExplicitEntitlement(header) || hasPremiumStatusLabel(header)

    private fun hasPremiumStatusLabel(element: JsonElement, scopedToStatusField: Boolean = false): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            val scoped = scopedToStatusField || key in activeHeaderStatusKeys
            (scoped && statusValueConfirmsPremium(value)) || hasPremiumStatusLabel(value, scoped)
        }
        is JsonArray -> element.any { hasPremiumStatusLabel(it, scopedToStatusField) }
        else -> scopedToStatusField && statusValueConfirmsPremium(element)
    }

    private fun statusValueConfirmsPremium(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            (key == "iconType" && value.jsonPrimitiveOrNull()?.contentOrNull?.let { it in premiumStatusIcons } == true) ||
                statusValueConfirmsPremium(value)
        }
        is JsonArray -> element.any(::statusValueConfirmsPremium)
        else -> {
            val value = element.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.lowercase()
            value in premiumStatusLabels ||
                element.jsonPrimitiveOrNull()?.contentOrNull?.let { it in premiumStatusIcons } == true
        }
    }

    private fun JsonElement.jsonPrimitiveOrNull() = this as? kotlinx.serialization.json.JsonPrimitive
}
