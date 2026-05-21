package com.streamcloud.app.data.ytmusic.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
        val descrambled = descramble(scrambled[1].jsonPrimitive.content)
        Json.parseToJsonElement(descrambled).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    val messageId     = challengeData[0].jsonPrimitive.content
    val interpreterHash = challengeData[3].jsonPrimitive.content
    val program       = challengeData[4].jsonPrimitive.content
    val globalName    = challengeData[5].jsonPrimitive.content
    val clientExperimentsStateBlob = challengeData[7].jsonPrimitive.content

    val safeScriptWrapped = challengeData[1]
        .takeIf { it !is JsonNull }?.jsonArray?.find { it.jsonPrimitive.isString }
    val trustedResourceUrlWrapped = challengeData[2]
        .takeIf { it !is JsonNull }?.jsonArray?.find { it.jsonPrimitive.isString }

    return Json.encodeToString<JsonObject>(JsonObject(mapOf(
        "messageId" to JsonPrimitive(messageId),
        "interpreterJavascript" to JsonObject(mapOf(
            "privateDoNotAccessOrElseSafeScriptWrappedValue" to (safeScriptWrapped ?: JsonNull),
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (trustedResourceUrlWrapped ?: JsonNull),
        )),
        "interpreterHash" to JsonPrimitive(interpreterHash),
        "program"    to JsonPrimitive(program),
        "globalName" to JsonPrimitive(globalName),
        "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob),
    )))
}

fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val arr = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    return base64ToU8(arr[0].jsonPrimitive.content) to arr[1].jsonPrimitive.long
}

fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
        .replace("+", "-")
        .replace("/", "_")

private fun descramble(scrambledChallenge: String): String =
    base64ToBytes(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToBytes(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(",") { it.toUByte().toString() } + "])"

private fun base64ToBytes(base64: String): ByteArray {
    val mod = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return android.util.Base64.decode(mod, android.util.Base64.DEFAULT)
        ?: throw PoTokenException("Cannot base64 decode: $base64")
}
