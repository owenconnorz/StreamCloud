package com.streamcloud.app.data.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.TimeUnit

/**
 * Discovers Sonos zone players on the local LAN via UPnP SSDP.
 *
 * Sends an M-SEARCH multicast to 239.255.255.250:1900 targeting the Sonos
 * ZonePlayer device type. Each responding device sends its description URL;
 * we fetch that XML to extract the friendly room name and UDN (device UUID).
 *
 * Requires:
 *  - android.permission.CHANGE_WIFI_MULTICAST_STATE   (for MulticastSocket)
 *  - android.permission.ACCESS_WIFI_STATE             (for WifiManager lock)
 *  - android.permission.INTERNET
 *
 * Returns a deduplicated list of [SonosDevice] found within [timeoutMs].
 */
object SonosDiscovery {

    private const val TAG = "SonosDiscovery"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val M_SEARCH = """
        M-SEARCH * HTTP/1.1
        HOST: $SSDP_ADDRESS:$SSDP_PORT
        MAN: "ssdp:discover"
        MX: 3
        ST: $SEARCH_TARGET
        
        
    """.trimIndent().replace("\n", "\r\n").encodeToByteArray()

    suspend fun discover(context: Context, timeoutMs: Long = 4_000): List<SonosDevice> =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("sonos_ssdp").apply { acquire() }
            val found = mutableMapOf<String, SonosDevice>()
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val socket = MulticastSocket(0)
                socket.soTimeout = timeoutMs.toInt()
                socket.timeToLive = 4
                socket.joinGroup(group)

                // Send M-SEARCH
                socket.send(DatagramPacket(M_SEARCH, M_SEARCH.size, group, SSDP_PORT))
                Log.d(TAG, "M-SEARCH sent")

                // Collect responses until timeout
                withTimeoutOrNull(timeoutMs) {
                    val buf = ByteArray(4096)
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        val received = try { socket.receive(pkt); true } catch (_: Exception) { false }
                        if (!received) break
                        val response = String(pkt.data, 0, pkt.length)
                        val location = response.lines()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        if (!location.isNullOrBlank()) {
                            val device = fetchDeviceDescription(location)
                            if (device != null && device.udn !in found) {
                                found[device.udn] = device
                                Log.d(TAG, "Found: ${device.name} @ ${device.host}")
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

    private fun fetchDeviceDescription(location: String): SonosDevice? {
        return try {
            val req = Request.Builder().url(location).build()
            val body = http.newCall(req).execute().use { it.body?.string() } ?: return null
            val name = Regex("<friendlyName>([^<]+)</friendlyName>")
                .find(body)?.groupValues?.get(1)?.trim() ?: "Sonos"
            val udn = Regex("<UDN>uuid:([^<]+)</UDN>")
                .find(body)?.groupValues?.get(1)?.trim() ?: return null
            val host = location.removePrefix("http://")
                .substringBefore("/").substringBefore(":")
            val port = location.removePrefix("http://")
                .substringBefore("/").substringAfter(":", "1400").toIntOrNull() ?: 1400
            SonosDevice(udn = udn, name = name, host = host, port = port)
        } catch (e: Exception) {
            Log.w(TAG, "fetchDeviceDescription failed for $location: ${e.message}")
            null
        }
    }

    /**
     * Returns the phone's current WiFi IPv4 address (e.g. "192.168.1.5"),
     * used to build the proxy URL that Sonos will stream from.
     */
    fun localIp(context: Context): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else "%d.%d.%d.%d".format(
                ip and 0xff, (ip shr 8) and 0xff,
                (ip shr 16) and 0xff, (ip shr 24) and 0xff,
            )
        } catch (_: Exception) {
            null
        }
    }
}
