package com.streamcloud.app.ui.screens.adult

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubLoginNavigationTest {

    @Test
    fun allowsSecureThirdPartyFramesForCaptchaAndLoginWidgets() {
        assertTrue(
            isAllowedPornhubLoginNavigation(
                scheme = "https",
                host = "challenges.cloudflare.com",
                isMainFrame = false,
            ),
        )
    }

    @Test
    fun stillRestrictsTopLevelNavigationToPornhubAndKnownLoginProviders() {
        assertTrue(
            isAllowedPornhubLoginNavigation(
                scheme = "https",
                host = "www.pornhub.com",
                isMainFrame = true,
            ),
        )
        assertTrue(
            isAllowedPornhubLoginNavigation(
                scheme = "https",
                host = "accounts.google.com",
                isMainFrame = true,
            ),
        )
        assertFalse(
            isAllowedPornhubLoginNavigation(
                scheme = "https",
                host = "unexpected.example",
                isMainFrame = true,
            ),
        )
    }

    @Test
    fun blocksInsecureExternalFrames() {
        assertFalse(
            isAllowedPornhubLoginNavigation(
                scheme = "http",
                host = "captcha.example",
                isMainFrame = false,
            ),
        )
    }
}