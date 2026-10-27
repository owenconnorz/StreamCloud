package com.streamcloud.app.data.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosDeviceCacheTest {
    @Test
    fun `cached devices round trip`() {
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

        assertEquals(devices, SonosDeviceCache.decode(SonosDeviceCache.encode(devices)))
    }

    @Test
    fun `cache decoder ignores invalid or corrupt entries`() {
        val cache = """
            [
              {"udn":"RINCON_1","name":"Office","host":"192.168.1.30","port":1400},
              {"udn":"RINCON_2","name":"Bad host","host":"speaker.local","port":1400},
              {"udn":"RINCON_3","name":"Bad port","host":"192.168.1.31","port":0}
            ]
        """.trimIndent()

        assertEquals(
            listOf(SonosDevice("RINCON_1", "Office", "192.168.1.30")),
            SonosDeviceCache.decode(cache),
        )
        assertTrue(SonosDeviceCache.decode("not-json").isEmpty())
    }
}
