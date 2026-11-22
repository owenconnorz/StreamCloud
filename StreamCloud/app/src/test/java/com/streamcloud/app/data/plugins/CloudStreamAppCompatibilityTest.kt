package com.streamcloud.app.data.plugins

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudStreamAppCompatibilityTest {
    @Test
    fun exposesTheCloudStreamAppCompanionMethodsUsedByStreamPlay() {
        val companion = CloudStreamApp.Companion::class.java

        assertNotNull(companion.getDeclaredMethod("getContext"))
        assertNotNull(
            companion.getDeclaredMethod(
                "setKey",
                String::class.java,
                String::class.java,
                Any::class.java,
            ),
        )
    }

    @Test
    fun exposesThePreferenceMethodsAndGsonTypesUsedByProviders() {
        assertNotNull(
            DataStore::class.java.getDeclaredMethod(
                "getSharedPrefs",
                Context::class.java,
            ),
        )
        assertNotNull(
            DataStore::class.java.getDeclaredMethod(
                "getFolderName",
                String::class.java,
                String::class.java,
            ),
        )
        assertNotNull(Class.forName("com.google.gson.Gson"))
    }
}
