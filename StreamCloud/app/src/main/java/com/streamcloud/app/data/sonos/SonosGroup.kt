package com.streamcloud.app.data.sonos

/**
 * Represents a Sonos playback group — either a single speaker or multiple speakers
 * bonded together.  All UPnP commands must be sent to the coordinator; sending
 * SetAVTransportURI to a non-coordinator always results in UPnP error 701.
 */
data class SonosGroup(
    val id: String,
    val coordinatorUdn: String,
    val coordinatorHost: String,
    val coordinatorPort: Int,
    /** Display names of every visible member (excludes SUBs / BRIDGEs). */
    val memberNames: List<String>,
) {
    /** "Living Room" or "Living Room + Kitchen" */
    val displayName: String get() = memberNames.joinToString(" + ")

    /** True when 2+ physical speakers share this group. */
    val isMultiRoom: Boolean get() = memberNames.size > 1

    /** SonosDevice wrapping the group coordinator — use for all SOAP calls. */
    val coordinatorDevice: SonosDevice
        get() = SonosDevice(
            udn  = coordinatorUdn,
            name = displayName,
            host = coordinatorHost,
            port = coordinatorPort,
        )

    companion object {

        // ── XML unescaper ────────────────────────────────────────────────────
        private fun String.xmlUnescape() = this
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

        /**
         * Parse the inner XML returned by GetZoneGroupState (after XML-unescaping
         * the SOAP envelope value).  Invisible sub-devices (SUB woofers, BRIDGEs)
         * are excluded so they don't appear as standalone targets.
         */
        fun parseZoneGroupXml(soapResponseBody: String): List<SonosGroup> {
            // The ZoneGroupState value is itself XML-escaped inside the SOAP body.
            val rawXml = Regex(
                "<ZoneGroupState>([\\s\\S]*?)</ZoneGroupState>"
            ).find(soapResponseBody)?.groupValues?.get(1)?.xmlUnescape()
                ?: return emptyList()

            val groups = mutableListOf<SonosGroup>()

            // Match every <ZoneGroup ...>...</ZoneGroup> block
            val groupRe = Regex(
                """<ZoneGroup\b[^>]*Coordinator="([^"]+)"[^>]*ID="([^"]+)"[^>]*>([\s\S]*?)</ZoneGroup>"""
            )
            val memberRe = Regex("""<ZoneGroupMember\b([^>]*?)(?:/>|>)""")
            val attrRe   = Regex("""(\w+)="([^"]*)"""")

            for (gm in groupRe.findAll(rawXml)) {
                val coordinatorUdn = gm.groupValues[1]
                val groupId        = gm.groupValues[2]
                val body           = gm.groupValues[3]

                data class Mbr(val uuid: String, val zone: String, val location: String)
                val members = mutableListOf<Mbr>()

                for (mm in memberRe.findAll(body)) {
                    val attrs = attrRe.findAll(mm.groupValues[1])
                        .associate { it.groupValues[1] to it.groupValues[2] }
                    // Skip invisible sub-devices (SUB, BRIDGE, satellite channels)
                    if (attrs["Invisible"] == "1") continue
                    val uuid     = attrs["UUID"]     ?: continue
                    val zone     = attrs["ZoneName"] ?: uuid
                    val location = attrs["Location"] ?: ""
                    members.add(Mbr(uuid, zone, location))
                }

                if (members.isEmpty()) continue

                // Coordinator's Location URL carries its IP + port
                val coord = members.firstOrNull { it.uuid == coordinatorUdn } ?: members.first()
                val locPath = coord.location.removePrefix("http://").substringBefore("/")
                val host    = locPath.substringBefore(":")
                val port    = locPath.substringAfter(":", "1400").toIntOrNull() ?: 1400

                if (host.isBlank()) continue

                groups.add(SonosGroup(
                    id              = groupId,
                    coordinatorUdn  = coordinatorUdn,
                    coordinatorHost = host,
                    coordinatorPort = port,
                    memberNames     = members.map { it.zone },
                ))
            }
            return groups
        }

        /**
         * Fallback: wrap individual SSDP-discovered devices as single-member groups
         * when GetZoneGroupState is unavailable (very old firmware, firewall, etc.).
         */
        fun fromDevices(devices: List<SonosDevice>): List<SonosGroup> = devices.map { dev ->
            SonosGroup(
                id              = dev.udn,
                coordinatorUdn  = dev.udn,
                coordinatorHost = dev.host,
                coordinatorPort = dev.port,
                memberNames     = listOf(dev.name),
            )
        }
    }
}
