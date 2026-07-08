package com.streamcloud.app.data.plugins

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.amapIndexed
import com.lagradost.cloudstream3.amapNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and coverage tests for the CloudStream plugin-compatibility layer.
 */
class PluginCompatibilityTest {

    @Test
    fun amapOnListReceiverDoublesEveryElement() = runBlocking {
        val input: List<Int> = listOf(1, 2, 3, 4, 5)
        val result = input.amap { it * 2 }
        assertEquals(listOf(2, 4, 6, 8, 10), result)
    }

    @Test
    fun parCollectionsKtExposesAmapWithListParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val amapMethods = parCollectionsClass.methods.filter { it.name == "amap" }
        assertTrue(
            "ParCollectionsKt must have at least one method named exactly 'amap'",
            amapMethods.isNotEmpty(),
        )
        val listOverload = amapMethods.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.first() == java.util.List::class.java
        }
        assertNotNull("ParCollectionsKt must expose amap(java.util.List, ...)", listOverload)
    }

    @Test
    fun amapIndexedOnListReceiverUsesIndexAndValue() = runBlocking {
        val input = listOf("a", "b", "c")
        val result = input.amapIndexed { index, value -> "$index:$value" }
        assertEquals(listOf("0:a", "1:b", "2:c"), result)
    }

    @Test
    fun parCollectionsKtExposesAmapIndexedWithListParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val methods = parCollectionsClass.methods.filter { it.name == "amapIndexed" }
        assertTrue(
            "ParCollectionsKt must have at least one method named exactly 'amapIndexed'",
            methods.isNotEmpty(),
        )
        val listOverload = methods.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes.first() == java.util.List::class.java
        }
        assertNotNull("ParCollectionsKt must expose amapIndexed(java.util.List, ...)", listOverload)
    }

    @Test
    fun amapNotNullDropsNulls() = runBlocking {
        val input = listOf(1, 2, 3, 4)
        val result = input.amapNotNull { n -> if (n % 2 == 0) n else null }
        assertEquals(listOf(2, 4), result)
        assertFalse(result.contains(1))
    }
}
