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
            val zoneXml = SonosController.getZoneGroupState(anyDevice)
                ?: return@withContext emptyList()
            val deviceMap = allDevices.associateBy { it.udn }
            val groups = mutableListOf<SonosGroup>()
            val groupRegex = Regex("""<ZoneGroup\s[^>]*Coordinator="([^"]+)"[^>]*ID="([^"]+)"[^>]*>([\s\S]*?)</ZoneGroup>""")
            val memberRegex = Regex("""<ZoneGroupMember\s[^>]*UUID="([^"]+)"[^>]*/?>""")
            groupRegex.findAll(zoneXml).forEach { gm ->
                val coordinatorUdn = gm.groupValues[1]
                val groupId = gm.groupValues[2]
                val membersXml = gm.groupValues[3]
                val coordinator = deviceMap[coordinatorUdn] ?: return@forEach
                val members = memberRegex.findAll(membersXml)
                    .mapNotNull { deviceMap[it.groupValues[1]] }
                    .toList()
                if (members.size >= 2) {
                    val suffix = if (members.size == 2) "+ 1" else "+ ${members.size - 1}"
                    groups.add(SonosGroup(groupId, "${coordinator.name} $suffix", coordinator, members))
                }
            }
            groups
        }

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
