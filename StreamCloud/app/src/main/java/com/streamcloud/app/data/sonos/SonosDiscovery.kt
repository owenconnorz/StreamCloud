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


                socket.send(DatagramPacket(M_SEARCH, M_SEARCH.size, group, SSDP_PORT))
                Log.d(TAG, "M-SEARCH sent")


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


    suspend fun buildGroups(anyDevice: SonosDevice, allDevices: List<SonosDevice>): List<SonosGroup> =
        withContext(Dispatchers.IO) {
            val soapBody = SonosController.getZoneGroupState(anyDevice)
                ?: return@withContext emptyList()
            SonosGroup.parseZoneGroupXml(soapBody)
        }

    fun localIp(context: Context): String? {
        // Prefer NetworkInterface enumeration — works correctly on Android 12+ where
        // WifiManager.connectionInfo is deprecated and may return 0 for the IP address.
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (intf in ifaces.asSequence()) {
                    if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                    // Only consider WiFi or Ethernet — Sonos communicates on the local LAN
                    val name = intf.name
                    if (!name.startsWith("wlan") && !name.startsWith("eth")) continue
                    for (addr in intf.inetAddresses.asSequence()) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            Log.d(TAG, "localIp via NetworkInterface ($name): ${addr.hostAddress}")
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: deprecated WifiManager API (Android < 12 or missing CHANGE_NETWORK_STATE)
        return try {
            @Suppress("DEPRECATION")
            val ip = (context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .connectionInfo.ipAddress
            if (ip == 0) null
            else "%d.%d.%d.%d".format(
                ip and 0xff, (ip shr 8) and 0xff,
                (ip shr 16) and 0xff, (ip shr 24) and 0xff,
            ).also { Log.d(TAG, "localIp via WifiManager: $it") }
        } catch (_: Exception) {
            null
        }
    }
}
