package com.streamcloud.app.data.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosDeviceCacheTest {
    @Test
    fun `cached speakers and resolved room groups round trip`() {
        val devices = listOf(
            SonosDevice(
                udn = "RINCON_001",
                name = "Living Room",
                host = "192.168.1.20",
                port = 1400,
            ),
            SonosDevice(
                udn = "RINCON_002",
                name = "Kitchen",
                host = "192.168.1.21",
                port = 1400,
            ),
        )
        val groups = listOf(
            SonosGroup(
                id = "group-living-room",
                coordinatorUdn = "RINCON_001",
                coordinatorHost = "192.168.1.20",
                coordinatorPort = 1400,
                memberNames = listOf("Living Room", "Kitchen"),
            ),
        )

        assertEquals(
            SonosDeviceCache.Snapshot(devices, groups),
            SonosDeviceCache.decode(SonosDeviceCache.encode(devices, groups)),
        )
    }

    @Test
    fun `cache decoder ignores invalid entries and corrupt or legacy cache`() {
        val cache = """
            {
              "version": 1,
              "devices": [
                {"udn":"RINCON_1","name":"Office","host":"192.168.1.30","port":1400},
                {"udn":"RINCON_2","name":"Bad host","host":"speaker.local","port":1400},
                {"udn":"RINCON_3","name":"Bad port","host":"192.168.1.31","port":0}
              ],
              "groups": [
                {"id":"ok","coordinatorUdn":"RINCON_1","coordinatorHost":"192.168.1.30","coordinatorPort":1400,"memberNames":["Office"]},
                {"id":"bad","coordinatorUdn":"RINCON_2","coordinatorHost":"speaker.local","coordinatorPort":1400,"memberNames":["Bad"]}
              ]
            }
        """.trimIndent()

        assertEquals(
            SonosDeviceCache.Snapshot(
                devices = listOf(SonosDevice("RINCON_1", "Office", "192.168.1.30")),
                groups = listOf(
                    SonosGroup(
                        id = "ok",
                        coordinatorUdn = "RINCON_1",
                        coordinatorHost = "192.168.1.30",
                        coordinatorPort = 1400,
                        memberNames = listOf("Office"),
                    ),
                ),
            ),
            SonosDeviceCache.decode(cache),
        )
        assertTrue(SonosDeviceCache.decode("not-json") == null)
        val legacy = """[{"udn":"RINCON_1","name":"Old cache","host":"192.168.1.30","port":1400}]"""
        assertTrue(SonosDeviceCache.decode(legacy) == null)
        assertEquals(
            listOf(SonosDevice("RINCON_1", "Old cache", "192.168.1.30")),
            SonosDeviceCache.decodeLegacyDevices(legacy),
        )
    }
}
