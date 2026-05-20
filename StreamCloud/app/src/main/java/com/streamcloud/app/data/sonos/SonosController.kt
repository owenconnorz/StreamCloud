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
    private const val AV_TRANSPORT_PATH    = "/MediaRenderer/AVTransport/Control"
    private const val RENDERING_SERVICE    = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERING_PATH       = "/MediaRenderer/RenderingControl/Control"

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

    // ── RenderingControl (volume) ──────────────────────────────────────────

    /** Returns the Sonos zone player's Master volume (0–100), or null on error. */
    suspend fun getVolume(device: SonosDevice): Int? = withContext(Dispatchers.IO) {
        try {
            val envelope = renderingEnvelope(
                "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>",
            )
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$RENDERING_PATH")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("SOAPACTION", "\"$RENDERING_SERVICE#GetVolume\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()
            val body = http.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            Regex("<CurrentVolume>([0-9]+)</CurrentVolume>")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getVolume failed: ${e.message}")
            null
        }
    }

    /** Sets the Sonos zone player's Master volume to [volume] (0–100). */
    suspend fun setVolume(device: SonosDevice, volume: Int): Boolean {
        val clamped = volume.coerceIn(0, 100)
        return renderingSoap(
            device,
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>",
        )
    }

    private suspend fun renderingSoap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val envelope = renderingEnvelope(action, body)
                val req = Request.Builder()
                    .url("http://${device.host}:${device.port}$RENDERING_PATH")
                    .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                    .header("SOAPACTION", "\"$RENDERING_SERVICE#$action\"")
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .build()
                val resp = http.newCall(req).execute()
                val ok = resp.isSuccessful
                if (!ok) Log.w(TAG, "Rendering SOAP $action → HTTP ${resp.code}")
                resp.body?.close()
                ok
            } catch (e: Exception) {
                Log.w(TAG, "Rendering SOAP $action failed: ${e.message}")
                false
            }
        }

    private fun renderingEnvelope(action: String, body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:$action xmlns:u="$RENDERING_SERVICE">
              $body
            </u:$action>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

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
                if (!ok) {
                    val errBody = runCatching { resp.body?.string() }.getOrNull() ?: ""
                    Log.w(TAG, "SOAP $action → HTTP ${resp.code}: $errBody")
                } else {
                    resp.body?.close()
                }
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

    /**
     * Minimal DIDL-Lite metadata so Sonos shows the track title on its display.
     *
     * Notes:
     *  - parentID must be "-1" (UPnP root), NOT "0" — strict Sonos firmwares
     *    return a SOAP fault for parentID="0" on external streams.
     *  - restricted="true" is the correct boolean string per UPnP spec.
     *  - protocolInfo uses audio/mp4 matching the AAC-LC stream YouTube serves
     *    via the ANDROID_MUSIC Innertube client.
     *  - Namespaces are declared on the root element in the order Sonos expects.
     */
    private fun buildDIDL(title: String, uri: String): String {
        val safeTitle = title.xmlEscape()
        val safeUri = uri.xmlEscape()
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"-1\" restricted=\"true\">" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mp4:*\">$safeUri</res>" +
            "</item>" +
            "</DIDL-Lite>"
    }

    private fun String.xmlEscape() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
