package com.streamcloud.app.data.plugins

import com.lagradost.cloudstream3.CloudStreamApp
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
}
