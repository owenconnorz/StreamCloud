package com.streamcloud.app.ui.screens.adult

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XnxxLoginNavigationTest {

    @Test
    fun acceptsOfficialXnxxHosts() {
        assertTrue(isXnxxLoginHost("xnxx.com"))
        assertTrue(isXnxxLoginHost("www.xnxx.com"))
        assertTrue(isXnxxLoginHost("account.xnxx.com"))
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertFalse(isXnxxLoginHost("xnxx.com.example.org"))
        assertFalse(isXnxxLoginHost("notxnxx.com"))
    }

    @Test
    fun allowsOnlySecureTopLevelXnxxPages() {
        assertTrue(isAllowedXnxxLoginNavigation("https", "www.xnxx.com", true))
        assertFalse(isAllowedXnxxLoginNavigation("http", "www.xnxx.com", true))
        assertFalse(isAllowedXnxxLoginNavigation("https", "example.org", true))
    }

    @Test
    fun allowsSecureVerificationSubframes() {
        assertTrue(isAllowedXnxxLoginNavigation("https", "captcha.example.org", false))
        assertFalse(isAllowedXnxxLoginNavigation("http", "captcha.example.org", false))
    }

    @Test
    fun recognizesAccountPathRegardlessOfTrailingSlash() {
        assertTrue(isXnxxAccountPath("/account/"))
        assertTrue(isXnxxAccountPath("/account"))
        assertFalse(isXnxxAccountPath("/"))
    }
}
