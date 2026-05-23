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
    private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val AV_TRANSPORT_PATH    = "/MediaRenderer/AVTransport/Control"
    private const val RENDERING_SERVICE    = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERING_PATH       = "/MediaRenderer/RenderingControl/Control"
    private const val TOPOLOGY_SERVICE     = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    private const val TOPOLOGY_PATH        = "/ZoneGroupTopology/Control"

    /** Last SOAP failure — shown in error UI for diagnostics. */
    @Volatile var lastSoapError: String = ""
        private set

    private val XML_MT = "text/xml; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val topologyHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // ── Zone Group Topology ────────────────────────────────────────────────────

    suspend fun getZoneGroupState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$TOPOLOGY_PATH")
                .post(soapBody("GetZoneGroupState", TOPOLOGY_SERVICE, "").toRequestBody(XML_MT))
                .header("SOAPACTION", "\"$TOPOLOGY_SERVICE#GetZoneGroupState\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("Connection", "close")
                .build()
            topologyHttp.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.w(TAG, "GetZoneGroupState failed: ${e.message}")
            null
        }
    }

    // ── AVTransport ────────────────────────────────────────────────────────────

    /**
     * SetAVTransportURI.
     *
     * [mimeType] must match what the proxy actually serves (e.g. "audio/mp4").
     * Sonos firmware validates the MIME type at SetAVTransportURI time — passing
     * an incorrect or unsupported MIME type causes UPnP error 714.
     *
     * Two strategies are tried:
     *   1. Full DIDL with correct protocolInfo (most compatible).
     *   2. Empty CurrentURIMetaData (absolute fallback for strict firmware).
     */
    suspend fun setUri(
        device: SonosDevice,
        streamUrl: String,
        title: String = "",
        mimeType: String = "audio/mp4",
    ): Boolean {
        val didl  = buildDIDL(title, streamUrl, mimeType)
        val body1 = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${streamUrl.xmlEscape()}</CurrentURI>" +
            "<CurrentURIMetaData>${didl.xmlEscape()}</CurrentURIMetaData>"
        if (soap(device, "SetAVTransportURI", body1)) return true

        Log.w(TAG, "setUri with DIDL failed [$lastSoapError] — retrying with empty metadata")
        val body2 = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${streamUrl.xmlEscape()}</CurrentURI>" +
            "<CurrentURIMetaData></CurrentURIMetaData>"
        return soap(device, "SetAVTransportURI", body2)
    }

    suspend fun play(device: SonosDevice): Boolean =
        soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    suspend fun pause(device: SonosDevice): Boolean =
        soap(device, "Pause", "<InstanceID>0</InstanceID>")

    suspend fun stop(device: SonosDevice): Boolean =
        soap(device, "Stop", "<InstanceID>0</InstanceID>")

    suspend fun getState(device: SonosDevice): String? = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(avRequest(device, "GetTransportInfo",
                "<InstanceID>0</InstanceID>")).execute()
            val body = resp.body?.string() ?: return@withContext null
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>")
                .find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "getState: ${e.message}")
            null
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────────────

    suspend fun getVolume(device: SonosDevice): Int? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}$RENDERING_PATH")
                .post(soapBody("GetVolume", RENDERING_SERVICE,
                    "<InstanceID>0</InstanceID><Channel>Master</Channel>").toRequestBody(XML_MT))
                .header("SOAPACTION", "\"$RENDERING_SERVICE#GetVolume\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("Connection", "close")
                .build()
            val body = http.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            Regex("<CurrentVolume>([0-9]+)</CurrentVolume>").find(body)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "getVolume: ${e.message}")
            null
        }
    }

    suspend fun setVolume(device: SonosDevice, volume: Int): Boolean = renderingSoap(
        device, "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0,100)}</DesiredVolume>",
    )

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun soapBody(action: String, service: String, innerBody: String) =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><u:$action xmlns:u=\"$service\">$innerBody</u:$action></s:Body>" +
        "</s:Envelope>"

    private fun avRequest(device: SonosDevice, action: String, innerBody: String): Request =
        Request.Builder()
            .url("http://${device.host}:${device.port}$AV_TRANSPORT_PATH")
            .post(soapBody(action, AV_TRANSPORT_SERVICE, innerBody).toRequestBody(XML_MT))
            .header("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("Connection", "close")
            .build()

    private suspend fun soap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resp = http.newCall(avRequest(device, action, body)).execute()
                val ok   = resp.isSuccessful
                if (!ok) {
                    val err  = runCatching { resp.body?.string() }.getOrNull() ?: ""
                    val upnp = Regex("<errorCode>(\\d+)</errorCode>").find(err)?.groupValues?.get(1)
                    lastSoapError = if (upnp != null) "UPnP $upnp (HTTP ${resp.code})"
                                    else              "HTTP ${resp.code}"
                    Log.w(TAG, "SOAP $action → $lastSoapError  body=$err")
                } else {
                    resp.body?.close()
                }
                ok
            } catch (e: Exception) {
                lastSoapError = "${e.javaClass.simpleName}: ${e.message ?: "timeout"}"
                Log.w(TAG, "SOAP $action failed: $lastSoapError")
                false
            }
        }

    private suspend fun renderingSoap(device: SonosDevice, action: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("http://${device.host}:${device.port}$RENDERING_PATH")
                    .post(soapBody(action, RENDERING_SERVICE, body).toRequestBody(XML_MT))
                    .header("SOAPACTION", "\"$RENDERING_SERVICE#$action\"")
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .header("Connection", "close")
                    .build()
                val resp = http.newCall(req).execute()
                val ok   = resp.isSuccessful
                if (!ok) Log.w(TAG, "Rendering SOAP $action → HTTP ${resp.code}")
                resp.body?.close()
                ok
            } catch (e: Exception) {
                Log.w(TAG, "Rendering SOAP $action failed: ${e.message}")
                false
            }
        }

    /**
     * DIDL-Lite metadata for Sonos.
     *
     * protocolInfo is set to the ACTUAL MIME type of the proxied stream.
     * Using the correct type (e.g. "audio/mp4" for M4A/AAC) prevents Sonos from
     * returning UPnP error 714 (Illegal MIME-type) at SetAVTransportURI time.
     *
     * Includes the Rincon namespace (xmlns:r) used by Sonos's own apps.
     */
    internal fun buildDIDL(title: String, uri: String, mimeType: String = "audio/mp4"): String {
        val t    = title.xmlEscape()
        val u    = uri.xmlEscape()
        val info = "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite" +
            " xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" +
            " xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
            "<item id=\"1\" parentID=\"-1\" restricted=\"true\">" +
            "<dc:title>$t</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<r:streamContent/>" +
            "<res protocolInfo=\"$info\">$u</res>" +
            "</item>" +
            "</DIDL-Lite>"
    }

    private fun String.xmlEscape() = replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
