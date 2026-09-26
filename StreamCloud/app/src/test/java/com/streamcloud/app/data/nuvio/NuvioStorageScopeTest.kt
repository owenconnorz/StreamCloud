package com.streamcloud.app.data.nuvio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioStorageScopeTest {

    @Test
    fun storageKeyIsStableAndDoesNotExposeIdentity() {
        val scope = NuvioStorageScope("user@example.com", "profile-1")

        assertEquals(scope.storageKey, NuvioStorageScope("user@example.com", "profile-1").storageKey)
        assertTrue(scope.storageKey.matches(Regex("[0-9a-f]{64}")))
        assertTrue("user@example.com" !in scope.storageKey)
        assertTrue("profile-1" !in scope.storageKey)
    }

    @Test
    fun storageKeySeparatesAccountsAndProfiles() {
        val original = NuvioStorageScope("account-a", "profile-1").storageKey

        assertNotEquals(original, NuvioStorageScope("account-b", "profile-1").storageKey)
        assertNotEquals(original, NuvioStorageScope("account-a", "profile-2").storageKey)
    }
}