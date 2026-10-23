package com.streamcloud.app.ui.screens.adult

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XFreeLoginNavigationTest {
    @Test
    fun allowsXFreeIdentityAndAgeVerificationPagesAsTopLevelHttpsNavigation() {
        assertTrue(isAllowedXFreeLoginNavigation("https", "xfree.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "www.xfree.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "accounts.google.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "appleid.apple.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "ageverif.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "www.ageverif.com", true))
        assertTrue(isAllowedXFreeLoginNavigation("https", "verify.ageverif.com", true))
    }

    @Test
    fun rejectsInsecureAndUntrustedTopLevelDestinations() {
        assertFalse(isAllowedXFreeLoginNavigation("http", "www.xfree.com", true))
        assertFalse(isAllowedXFreeLoginNavigation("http", "www.ageverif.com", true))
        assertFalse(isAllowedXFreeLoginNavigation("javascript", "xfree.com", true))
        assertFalse(isAllowedXFreeLoginNavigation("https", "xfree.com.example.net", true))
        assertFalse(isAllowedXFreeLoginNavigation("https", "ageverif.com.example.net", true))
        assertFalse(isAllowedXFreeLoginNavigation("https", "accounts.example.net", true))
    }

    @Test
    fun allowsHttpsVerificationSubframesButNotInsecureSubframes() {
        assertTrue(isAllowedXFreeLoginNavigation("https", "verification.example", false))
        assertFalse(isAllowedXFreeLoginNavigation("http", "verification.example", false))
    }

    @Test
    fun recognizesAgeVerifAccountConnectionSuccessOnlyOnItsOwnHost() {
        assertTrue(
            isXFreeVerificationSuccessPage(
                "ageverif.com",
                "AgeVerif\nAccount connection successful\nContinue",
            ),
        )
        assertTrue(
            isXFreeVerificationSuccessPage(
                "verify.ageverif.com",
                "Account Connection Successful",
            ),
        )
        assertFalse(
            isXFreeVerificationSuccessPage(
                "xfree.com",
                "Account connection successful",
            ),
        )
        assertFalse(
            isXFreeVerificationSuccessPage(
                "ageverif.com.example.net",
                "Account connection successful",
            ),
        )
        assertFalse(
            isXFreeVerificationSuccessPage(
                "ageverif.com",
                "Verification could not be completed",
            ),
        )
    }

    @Test
    fun ageVerificationSuccessAloneDoesNotConfirmAnXFreeSession() {
        assertFalse(hasConfirmedXFreeSession("ageverif.com", true))
        assertFalse(hasConfirmedXFreeSession("www.xfree.com", false))
        assertFalse(hasConfirmedXFreeSession("xfree.com.example.net", true))
        assertTrue(hasConfirmedXFreeSession("www.xfree.com", true))
    }
}
