package com.streamcloud.app.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object DlnaController {

    private const val TAG = "DlnaController"
    private const val AV_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val loadHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun setUri(
        device: DlnaDevice,
        streamUrl: String,
        title: String = "",
        mimeType: String = "video/mp4",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val didl = buildDidl(title, streamUrl, mimeType).xmlEscape()
            val urlEsc = streamUrl.xmlEscape()
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "SetAVTransportURI",
                body = "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>$urlEsc</CurrentURI>" +
                    "<CurrentURIMetaData>$didl</CurrentURIMetaData>",
            )
            val ok = soap(device, envelope, "SetAVTransportURI", loadHttp)
            Log.d(TAG, "SetAVTransportURI → $ok (${device.name})")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setUri error: ${e.message}")
            false
        }
    }

    suspend fun play(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "Play",
                body = "<InstanceID>0</InstanceID><Speed>1</Speed>",
            )
            soap(device, envelope, "Play")
        } catch (e: Exception) {
            Log.w(TAG, "play error: ${e.message}")
            false
        }
    }

    suspend fun pause(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "Pause",
                body = "<InstanceID>0</InstanceID>",
            )
            soap(device, envelope, "Pause")
        } catch (e: Exception) {
            Log.w(TAG, "pause error: ${e.message}")
            false
        }
    }

    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "Stop",
                body = "<InstanceID>0</InstanceID>",
            )
            soap(device, envelope, "Stop")
        } catch (e: Exception) {
            Log.w(TAG, "stop error: ${e.message}")
            false
        }
    }

    suspend fun seek(device: DlnaDevice, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val hms = formatHms(positionMs)
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "Seek",
                body = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$hms</Target>",
            )
            soap(device, envelope, "Seek")
        } catch (e: Exception) {
            Log.w(TAG, "seek error: ${e.message}")
            false
        }
    }

    data class PositionInfo(val positionMs: Long, val durationMs: Long)

    suspend fun getPositionInfo(device: DlnaDevice): PositionInfo? = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "GetPositionInfo",
                body = "<InstanceID>0</InstanceID>",
            )
            val body = soapBody(device, envelope, "GetPositionInfo") ?: return@withContext null
            val relTime = Regex("<RelTime>([^<]+)</RelTime>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim() ?: "0:00:00"
            val trackDuration = Regex("<TrackDuration>([^<]+)</TrackDuration>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim() ?: "0:00:00"
            PositionInfo(parseHms(relTime), parseHms(trackDuration))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getTransportState(device: DlnaDevice): String? = withContext(Dispatchers.IO) {
        try {
            val envelope = soapEnvelope(
                service = AV_SERVICE,
                action = "GetTransportInfo",
                body = "<InstanceID>0</InstanceID>",
            )
            val body = soapBody(device, envelope, "GetTransportInfo") ?: return@withContext null
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun soapEnvelope(service: String, action: String, body: String): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body>""" +
            """<u:$action xmlns:u="$service">$body</u:$action>""" +
            """</s:Body></s:Envelope>"""

    private fun soap(
        device: DlnaDevice,
        envelope: String,
        action: String,
        client: OkHttpClient = http,
    ): Boolean {
        val req = Request.Builder()
            .url(device.avTransportControlUrl)
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("SOAPACTION", "\"$AV_SERVICE#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.close()
            resp.isSuccessful.also {
                if (!it) Log.w(TAG, "$action HTTP ${resp.code} from ${device.name}")
            }
        }
    }

    private fun soapBody(
        device: DlnaDevice,
        envelope: String,
        action: String,
    ): String? {
        val req = Request.Builder()
            .url(device.avTransportControlUrl)
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("SOAPACTION", "\"$AV_SERVICE#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun buildDidl(title: String, url: String, mimeType: String): String {
        val safeTitle = title.xmlEscape()
        val safeUrl = url.xmlEscape()
        val upnpClass = if (mimeType.startsWith("audio")) "object.item.audioItem.musicTrack"
        else "object.item.videoItem"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="1" parentID="0" restricted="1">""" +
            """<dc:title>$safeTitle</dc:title>""" +
            """<upnp:class>$upnpClass</upnp:class>""" +
            """<res protocolInfo="http-get:*:$mimeType:*">$safeUrl</res>""" +
            """</item></DIDL-Lite>"""
    }

    private fun String.xmlEscape(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatHms(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun parseHms(hms: String): Long {
        val parts = hms.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            1 -> parts[0] * 1000
            else -> 0L
        }
    }
}
