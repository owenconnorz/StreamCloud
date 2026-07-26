package com.streamcloud.app.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.TimeUnit

object DlnaDiscovery {

    private const val TAG = "DlnaDiscovery"
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun discover(context: Context, timeoutMs: Long = 5_000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("dlna_ssdp").apply { acquire() }
            val found = mutableMapOf<String, DlnaDevice>()
            try {
                val group = InetAddress.getByName(SSDP_ADDR)
                val socket = MulticastSocket(0)
                socket.soTimeout = timeoutMs.toInt()
                socket.timeToLive = 4
                socket.joinGroup(group)

                SEARCH_TARGETS.forEach { st ->
                    val mSearch = buildMSearch(st)
                    socket.send(DatagramPacket(mSearch, mSearch.size, group, SSDP_PORT))
                }
                Log.d(TAG, "M-SEARCH sent for ${SEARCH_TARGETS.size} targets")

                withTimeoutOrNull(timeoutMs) {
                    val buf = ByteArray(8192)
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        val ok = try { socket.receive(pkt); true } catch (_: Exception) { false }
                        if (!ok) break
                        val response = String(pkt.data, 0, pkt.length)
                        val location = response.lines()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        if (!location.isNullOrBlank() && !found.containsKey(location)) {
                            val device = fetchDevice(location)
                            if (device != null && device.udn !in found) {
                                found[device.udn] = device
                                Log.d(TAG, "Found: ${device.name} @ ${device.host}:${device.port}")
                            }
                        }
                    }
                }
                socket.leaveGroup(group)
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "SSDP scan error: ${e.message}")
            } finally {
                lock.release()
            }
            found.values.toList()
        }

    private fun buildMSearch(st: String): ByteArray {
        val msg = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: $st\r\n\r\n"
        return msg.toByteArray()
    }

    private fun fetchDevice(locationUrl: String): DlnaDevice? {
        return try {
            val body = http.newCall(
                Request.Builder().url(locationUrl).build()
            ).execute().use { it.body?.string() } ?: return null

            val name = Regex("<friendlyName>([^<]+)</friendlyName>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim() ?: return null

            val udn = Regex("<UDN>uuid:([^<]+)</UDN>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim() ?: return null

            val avTransportPath = parseAvTransportControlUrl(body, locationUrl) ?: return null

            val host = locationUrl.removePrefix("http://").substringBefore("/").substringBefore(":")
            val portStr = locationUrl.removePrefix("http://").substringBefore("/")
                .substringAfter(":", "")
            val port = portStr.toIntOrNull() ?: 80

            DlnaDevice(
                udn = udn,
                name = name,
                host = host,
                port = port,
                avTransportControlPath = avTransportPath,
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchDevice failed for $locationUrl: ${e.message}")
            null
        }
    }

    private fun parseAvTransportControlUrl(xml: String, locationBase: String): String? {
        val avSection = Regex(
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>(.*?)</service>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(xml)?.groupValues?.get(1) ?: return null

        val controlUrl = Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
            .find(avSection)?.groupValues?.get(1)?.trim() ?: return null

        return if (controlUrl.startsWith("/")) controlUrl
        else "/$controlUrl"
    }
}
