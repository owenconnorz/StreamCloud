package com.streamcloud.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubRepositoryTest {

    @Test
    fun authenticatedRequestsKeepSessionAndAgeCookies() {
        val cookies = pornhubRequestCookieHeader("session=authenticated; locale=en")

        assertTrue(cookies.contains("session=authenticated"))
        assertTrue(cookies.contains("locale=en"))
        assertTrue(cookies.contains("accessAgeDisclaimerPH=1"))
        assertTrue(cookies.contains("platform=mobile"))
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