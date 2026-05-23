package com.streamcloud.app.data.sonos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SonosController {

    private const val TAG = "SonosController"
    private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val AV_TRANSPORT_PATH    = "/MediaRenderer/AVTransport/Control"
    private const val RENDERING_SERVICE    = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERING_PATH       = "/MediaRenderer/RenderingControl/Control"
    private const val TOPOLOGY_SERVICE     = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    private const val TOPOLOGY_PATH        = "/ZoneGroupTopology/Control"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Zone Group Topology ────────────────────────────────────────────────────

    /**
     * Fetch the full Sonos group topology from any discovered device.
     * Returns the raw SOAP response body (caller handles parsing via SonosGroup).
     */
    suspend fun getZoneGroupState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:GetZoneGroupState xmlns:u="$TOPOLOGY_SERVICE">
                    </u:GetZoneGroupState>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$TOPOLOGY_PATH")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("SOAPACTION", "\"$TOPOLOGY_SERVICE#GetZoneGroupState\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.w(TAG, "GetZoneGroupState failed: ${e.message}")
            null
        }
    }

    // ── AVTransport ────────────────────────────────────────────────────────────

    suspend fun setUri(device: SonosDevice, streamUrl: String, title: String = ""): Boolean =
        soap(
            device = device,
            action = "SetAVTransportURI",
            body   = """
                <InstanceID>0</InstanceID>
                <CurrentURI>${streamUrl.xmlEscape()}</CurrentURI>
                <CurrentURIMetaData>${buildDIDL(title, streamUrl).xmlEscape()}</CurrentURIMetaData>
            """.trimIndent(),
        )

    suspend fun play(device: SonosDevice): Boolean =
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    suspend fun pause(device: SonosDevice): Boolean =
        soap(device, "Pause", "<InstanceID>0</InstanceID>")

    suspend fun stop(device: SonosDevice): Boolean =
        soap(device, "Stop", "<InstanceID>0</InstanceID>")

    suspend fun getState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(soapRequest(device, "GetTransportInfo",
                "<InstanceID>0</InstanceID>")).execute()
            val body = resp.body?.string() ?: return@withContext null
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>")
                .find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "getState failed: ${e.message}")
            null
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────────────

    suspend fun getVolume(device: SonosDevice): Int? = withContext(Dispatchers.IO) {
        try {
            val envelope = renderingEnvelope("GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>")
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$RENDERING_PATH")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("SOAPACTION", "\"$RENDERING_SERVICE#GetVolume\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()
            val body = http.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext null
            Regex("<CurrentVolume>([0-9]+)</CurrentVolume>")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getVolume failed: ${e.message}")
            null
        }
    }

    suspend fun setVolume(device: SonosDevice, volume: Int): Boolean = renderingSoap(
        device, "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0,100)}</DesiredVolume>",
    )

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun soapRequest(device: SonosDevice, action: String, body: String): Request =
        Request.Builder()
            .url("http://${device.host}:${device.port}$AV_TRANSPORT_PATH")
            .post(soapEnvelope(action, body).toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()

    private suspend fun soap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resp = http.newCall(soapRequest(device, action, body)).execute()
                val ok = resp.isSuccessful
                if (!ok) {
                    val err = runCatching { resp.body?.string() }.getOrNull() ?: ""
                    Log.w(TAG, "SOAP $action → HTTP ${resp.code}: $err")
                } else {
                    resp.body?.close()
                }
                ok
            } catch (e: Exception) {
                Log.w(TAG, "SOAP $action failed: ${e.message}")
                false
            }
        }

    private suspend fun renderingSoap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("http://${device.host}:${device.port}$RENDERING_PATH")
                    .post(renderingEnvelope(action, body).toRequestBody("text/xml; charset=utf-8".toMediaType()))
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

    private fun soapEnvelope(action: String, body: String) = """
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

    private fun renderingEnvelope(action: String, body: String) = """
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

    /**
     * DIDL-Lite metadata block.
     *
     * protocolInfo uses wildcard MIME + DLNA.ORG_OP=01 (byte-range supported),
     * so Sonos won't reject the stream with error 714 (Illegal MIME-type)
     * regardless of whether YouTube delivers audio/webm, audio/mp4, or video/mp4.
     */
    internal fun buildDIDL(title: String, uri: String): String {
        val safeTitle = title.xmlEscape()
        val safeUri   = uri.xmlEscape()
        return "<DIDL-Lite " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"-1\" restricted=\"true\">" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:*:DLNA.ORG_OP=01;" +
            "DLNA.ORG_FLAGS=01700000000000000000000000000000\">$safeUri</res>" +
            "</item>" +
            "</DIDL-Lite>"
    }

    private fun String.xmlEscape() = this
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
