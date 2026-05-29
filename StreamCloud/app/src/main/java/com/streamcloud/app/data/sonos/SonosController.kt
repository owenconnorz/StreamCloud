package com.streamcloud.app.data.sonos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SonosController {

    private const val TAG = "SonosController"
    private const val AV_TRANSPORT_SERVICE  = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val AV_TRANSPORT_PATH     = "/MediaRenderer/AVTransport/Control"
    private const val RENDERING_SERVICE     = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERING_PATH        = "/MediaRenderer/RenderingControl/Control"
    private const val ZONE_TOPOLOGY_SERVICE = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    private const val ZONE_TOPOLOGY_PATH    = "/ZoneGroupTopology/Control"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // SetAVTransportURI causes Sonos to synchronously probe the stream URI (HEAD/GET) before
    // returning its SOAP response. Proxy resolution can take several seconds, so give this
    // call a much longer read timeout than the general 8-second client.
    private val setUriHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns null on success, or a short error description on failure.
     * Written as a compact single-line SOAP envelope WITHOUT s:encodingStyle —
     * UPnP 1.1 (Sonos S2) prohibits that attribute and may return 402 when it is present.
     */
    suspend fun setUri(
        device: SonosDevice,
        streamUrl: String,
        title: String = "",
        mimeType: String = "audio/mp4",
    ): String? = withContext(Dispatchers.IO) {
        try {
            val didlEscaped  = buildDIDL(title, streamUrl, mimeType).xmlEscape()
            val streamEscaped = streamUrl.xmlEscape()
            // Compact, single-line SOAP body — no extra whitespace that might confuse parsers.
            // No s:encodingStyle on the Envelope (prohibited by UPnP 1.1 / Sonos S2).
            val envelope =
                """<?xml version="1.0" encoding="utf-8"?>""" +
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
                """<s:Body>""" +
                """<u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE">""" +
                """<InstanceID>0</InstanceID>""" +
                """<CurrentURI>$streamEscaped</CurrentURI>""" +
                """<CurrentURIMetaData>$didlEscaped</CurrentURIMetaData>""" +
                """</u:SetAVTransportURI>""" +
                """</s:Body>""" +
                """</s:Envelope>"""
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$AV_TRANSPORT_PATH")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#SetAVTransportURI\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()
            val resp = setUriHttp.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.body?.close()
                null  // success
            } else {
                val errBody = runCatching { resp.body?.string() }.getOrNull() ?: ""
                val faultCode = Regex("<errorCode>([^<]+)</errorCode>")
                    .find(errBody)?.groupValues?.get(1) ?: "?"
                val faultDesc = Regex("<errorDescription>([^<]+)</errorDescription>")
                    .find(errBody)?.groupValues?.get(1) ?: ""
                // Include Sonos host + stream URL so user can report exact context
                val msg = "Sonos ${device.host}:${device.port} → HTTP ${resp.code} " +
                    "(UPnP $faultCode${if (faultDesc.isNotBlank()) ": $faultDesc" else ""}) " +
                    "stream=${streamUrl.substringAfter("//").take(30)}"
                Log.w(TAG, "setUri failed: $msg | errBody=${errBody.take(300)}")
                msg
            }
        } catch (e: Exception) {
            val msg = "SetAVTransportURI exception: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            Log.w(TAG, msg)
            msg
        }
    }

    suspend fun setUriBoolean(
        device: SonosDevice,
        streamUrl: String,
        title: String = "",
        mimeType: String = "audio/mp4",
    ): Boolean = setUri(device, streamUrl, title, mimeType) == null

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


    suspend fun getZoneGroupState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:GetZoneGroupState xmlns:u="$ZONE_TOPOLOGY_SERVICE">
                    </u:GetZoneGroupState>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$ZONE_TOPOLOGY_PATH")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("SOAPACTION", "\"$ZONE_TOPOLOGY_SERVICE#GetZoneGroupState\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.w(TAG, "getZoneGroupState failed: ${e.message}")
            null
        }
    }

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


    private fun buildDIDL(title: String, uri: String, mimeType: String = "audio/mp4"): String {
        val safeTitle = title.xmlEscape()
        val safeUri   = uri.xmlEscape()
        // Namespaces must use "metadata-1-n" (UPnP standard), NOT "metadata-1-0".
        // id="-1"/parentID="-1"/restricted="false" = transient item (not in queue).
        // Wildcard DLNA flags ("*") are maximally permissive across Sonos firmware versions.
        return "<DIDL-Lite " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-n:DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-n:upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
            "<item id=\"-1\" parentID=\"-1\" restricted=\"false\">" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mimeType:*\">$safeUri</res>" +
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
