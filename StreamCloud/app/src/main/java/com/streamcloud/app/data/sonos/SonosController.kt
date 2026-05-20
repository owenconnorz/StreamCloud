package com.streamcloud.app.data.sonos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends UPnP AV Transport SOAP commands to a Sonos zone player.
 *
 * Every public function is a suspend fun that executes on [Dispatchers.IO]
 * and returns true on HTTP 200, false on any error. Callers should not
 * retry — Sonos typically responds within 200 ms on a healthy LAN.
 *
 * Supported actions (all on urn:schemas-upnp-org:service:AVTransport:1):
 *  - [setUri]   SetAVTransportURI — load a stream URL
 *  - [play]     Play              — start / resume playback
 *  - [pause]    Pause             — pause playback
 *  - [stop]     Stop              — stop and unload
 *  - [getState] GetTransportInfo  — returns "PLAYING", "PAUSED_PLAYBACK", "STOPPED", etc.
 */
object SonosController {

    private const val TAG = "SonosController"
    private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun setUri(device: SonosDevice, streamUrl: String, title: String = ""): Boolean =
        soap(
            device = device,
            action = "SetAVTransportURI",
            body = """
                <InstanceID>0</InstanceID>
                <CurrentURI>${streamUrl.xmlEscape()}</CurrentURI>
                <CurrentURIMetaData>${buildDIDL(title, streamUrl).xmlEscape()}</CurrentURIMetaData>
            """.trimIndent(),
        )

    suspend fun play(device: SonosDevice): Boolean =
        soap(
            device = device,
            action = "Play",
            body = "<InstanceID>0</InstanceID><Speed>1</Speed>",
        )

    suspend fun pause(device: SonosDevice): Boolean =
        soap(
            device = device,
            action = "Pause",
            body = "<InstanceID>0</InstanceID>",
        )

    suspend fun stop(device: SonosDevice): Boolean =
        soap(
            device = device,
            action = "Stop",
            body = "<InstanceID>0</InstanceID>",
        )

    suspend fun getState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope("GetTransportInfo", "<InstanceID>0</InstanceID>")
            val resp = http.newCall(
                Request.Builder()
                    .url("http://${device.host}:${device.port}$AV_TRANSPORT_PATH")
                    .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                    .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#GetTransportInfo\"")
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .build(),
            ).execute()
            val body = resp.body?.string() ?: return@withContext null
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>")
                .find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "getState failed: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    private suspend fun soap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val envelope = soapEnvelope(action, body)
                val req = Request.Builder()
                    .url("http://${device.host}:${device.port}$AV_TRANSPORT_PATH")
                    .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                    .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#$action\"")
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .build()
                val resp = http.newCall(req).execute()
                val ok = resp.isSuccessful
                if (!ok) Log.w(TAG, "SOAP $action → HTTP ${resp.code}")
                resp.body?.close()
                ok
            } catch (e: Exception) {
                Log.w(TAG, "SOAP $action failed: ${e.message}")
                false
            }
        }

    private fun soapEnvelope(action: String, body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:$action xmlns:u="$AV_TRANSPORT_SERVICE">
              $body
            </u:$action>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /** Minimal DIDL-Lite metadata so Sonos shows the track title on its display. */
    private fun buildDIDL(title: String, uri: String): String {
        val safeTitle = title.xmlEscape()
        val safeUri = uri.xmlEscape()
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="1" parentID="0" restricted="1">
                <dc:title>$safeTitle</dc:title>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <res protocolInfo="http-get:*:audio/mpeg:*">$safeUri</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun String.xmlEscape() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
