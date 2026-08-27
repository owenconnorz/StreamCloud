package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubRepositoryTest {

    @Test
    fun authenticatedRequestsKeepPornhubProvidedCookies() {
        val cookies = pornhubRequestCookieHeader("session=authenticated; locale=en")

        assertTrue(cookies.contains("session=authenticated"))
        assertTrue(cookies.contains("locale=en"))
        assertTrue(cookies.contains("platform=mobile"))
        assertTrue(!cookies.contains("accessAgeDisclaimerPH"))
    }

    @Test
    fun existingPornhubCookieValuesAreNotOverridden() {
        assertEquals(
            "session=authenticated; accessAgeDisclaimerPH=1; platform=desktop",
            pornhubRequestCookieHeader(
                "session=authenticated; accessAgeDisclaimerPH=1; platform=desktop",
            ),
        )
    }
}