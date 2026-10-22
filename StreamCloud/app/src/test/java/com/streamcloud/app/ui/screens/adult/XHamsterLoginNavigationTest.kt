package com.streamcloud.app.ui.screens.adult

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XHamsterLoginNavigationTest {
    @Test
    fun allowsOfficialHttpsPagesInMainFrame() {
        assertTrue(isAllowedXHamsterLoginNavigation("https", "xhamster.com", true))
        assertTrue(isAllowedXHamsterLoginNavigation("https", "www.xhamster.com", true))
    }

    @Test
    fun blocksUntrustedTopLevelAndInsecureNavigation() {
        assertFalse(isAllowedXHamsterLoginNavigation("https", "xhamster.com.example.net", true))
        assertFalse(isAllowedXHamsterLoginNavigation("http", "xhamster.com", true))
        assertFalse(isAllowedXHamsterLoginNavigation("javascript", "xhamster.com", true))
    }

    @Test
    fun allowsSecureThirdPartyFramesForProviderVerification() {
        assertTrue(isAllowedXHamsterLoginNavigation("https", "verification.example", false))
        assertFalse(isAllowedXHamsterLoginNavigation("http", "verification.example", false))
    }
}
