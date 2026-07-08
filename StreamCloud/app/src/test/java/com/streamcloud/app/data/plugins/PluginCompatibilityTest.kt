package com.streamcloud.app.data.plugins

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.amapNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and coverage tests for the CloudStream plugin-compatibility layer.
 *
 * These are pure JVM tests (no Android context) that verify:
 *  1. ParCollections `amap` works for **both** List<A> and Iterable<A> receivers —
 *     the List overload was the root cause of the reported
 *     `NoSuchMethodError: No static method amap(Ljava/util/List; ...)`.
 *  2. The JVM method name for the List<A> overload is literally "amap" (not "amapList") —
 *     since `@JvmName("amapList")` would cause the NoSuchMethodError to persist.
 *  3. The extractor registry functions can be called without producing duplicate
 *     (name, mainUrl) pairs within a single registration set.
 *  4. Plugin load error messages are actionable: NoSuchMethodError on a CloudStream
 *     API method produces a hint about API/runtime version mismatch.
 */
class PluginCompatibilityTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ParCollections — regression guard for NoSuchMethodError amap(List,…)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun amapOnListReceiverDoublesEveryElement() = runBlocking {
        val input: List<Int> = listOf(1, 2, 3, 4, 5)
        // Calls the List<A> overload — the one that was missing and caused the
        // NoSuchMethodError: amap(Ljava/util/List;Lkotlin/jvm/functions/Function2;...)
        val result = input.amap { it * 2 }
        assertEquals(listOf(2, 4, 6, 8, 10), result)
    }

    /**
     * JVM-level regression: verifies that ParCollectionsKt exposes a static method
     * literally named "amap" whose first parameter is java.util.List.
     *
     * The reported crash was:
     *   NoSuchMethodError: No static method amap(Ljava/util/List;...)
     *
     * Root cause: the previous implementation used @JvmName("amapList") which renamed
     * the List-receiver overload to "amapList" at the JVM level.  Plugins compiled
     * against CloudStream's real ParCollections look for "amap(List,...)" — not
     * "amapList(List,...)" — so they crashed even after the overload was added.
     *
     * Fix: removing @JvmName allows both the Iterable and List overloads to coexist
     * under the name "amap" because their JVM descriptors differ
     * (java/lang/Iterable vs java/util/List as the first parameter).
     */
    @Test
    fun parCollectionsKtExposesAmapWithListParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val amapMethods = parCollectionsClass.methods.filter { it.name == "amap" }
        assertTrue(
            "ParCollectionsKt must have at least one method named exactly 'amap'",
            amapMethods.isNotEmpty(),
        )
        val listOverload = amapMethods.firstOrNull { m ->
            m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == java.util.List::class.java
        }
        assertNotNull(
            "ParCollectionsKt must have amap(List, ...) — this is the signature plugins call. " +
                "If this fails, check that @JvmName was NOT added to the List<A>.amap overload " +
                "in ParCollections.kt.",
            listOverload,
        )
    }

    @Test
    fun parCollectionsKtAlsoExposesAmapWithIterableParameter() {
        val parCollectionsClass = Class.forName("com.lagradost.cloudstream3.ParCollectionsKt")
        val iterableOverload = parCollectionsClass.methods.firstOrNull { m ->
            m.name == "amap" &&
                m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0] == Iterable::class.java
        }
        assertNotNull(
            "ParCollectionsKt must also expose amap(Iterable, ...) for plugins that use the generic path",
            iterableOverload,
        )
    }

    @Test
    fun amapOnIterableReceiverDoublesEveryElement() = runBlocking {
        val input: Iterable<Int> = listOf(1, 2, 3)
        // Calls the Iterable<A> overload — sanity-check the generic path still works
        val result = input.amap { it * 2 }
        assertEquals(listOf(2, 4, 6), result)
    }

    @Test
    fun amapNotNullOnListDropsNullResults() = runBlocking {
        val input: List<Int> = listOf(1, 2, 3, 4)
        val result = input.amapNotNull { n -> if (n % 2 == 0) n * 10 else null }
        assertEquals(listOf(20, 40), result)
    }

    @Test
    fun amapPreservesOrderForLargeList() = runBlocking {
        val n = 50
        val input = (1..n).toList()
        val result = input.amap { it * it }
        assertEquals(n, result.size)
        // Result must be ordered: result[i] == (i+1)^2
        result.forEachIndexed { idx, value ->
            assertEquals((idx + 1) * (idx + 1), value)
        }
    }

    @Test
    fun amapOnEmptyListReturnsEmptyList() = runBlocking {
        val result = emptyList<Int>().amap { it * 2 }
        assertTrue(result.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Extractor registry — no duplicate (name, mainUrl) within each set
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun registerAllExtractorsProducesNoDuplicateMainUrls() {
        // Use a fresh list so this test is isolated from other tests.
        val registry = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorApi>()

        // Replicate what registerAllExtractors() does but into our local list.
        // We call the real function against the real global list, then snapshot it.
        // To keep the test isolated we save and restore the global list.
        val globalBackup = com.lagradost.cloudstream3.utils.extractorApis.toList()
        com.lagradost.cloudstream3.utils.extractorApis.clear()
        try {
            com.lagradost.cloudstream3.extractors.registerAllExtractors()
            registry.addAll(com.lagradost.cloudstream3.utils.extractorApis)
        } finally {
            com.lagradost.cloudstream3.utils.extractorApis.clear()
            com.lagradost.cloudstream3.utils.extractorApis.addAll(globalBackup)
        }

        // Within registerAllExtractors, duplicate mainUrls should not appear
        // (each URL entry should be unique in the core set).
        val urls = registry.map { it.mainUrl.lowercase().trimEnd('/') }
        val duplicateUrls = urls.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(
            "Duplicate mainUrls in registerAllExtractors: $duplicateUrls",
            duplicateUrls.isEmpty(),
        )
    }

    @Test
    fun registerAllExtractorsIncludesKeyExtractors() {
        val globalBackup = com.lagradost.cloudstream3.utils.extractorApis.toList()
        com.lagradost.cloudstream3.utils.extractorApis.clear()
        try {
            com.lagradost.cloudstream3.extractors.registerAllExtractors()
            val names = com.lagradost.cloudstream3.utils.extractorApis.map { it.name }.toSet()
            // Core extractors that every CloudStream-compatible app must ship
            val required = listOf("StreamTape", "DoodLa", "MixDrop", "Voe", "FileMoon",
                "StreamWish", "VidHide", "Mp4Upload", "Uqload", "StreamSB",
                "VidSrc", "Kwik", "Superembed", "Okru", "SendVid")
            val missing = required.filterNot { it in names }
            assertTrue("Missing required extractors: $missing", missing.isEmpty())
        } finally {
            com.lagradost.cloudstream3.utils.extractorApis.clear()
            com.lagradost.cloudstream3.utils.extractorApis.addAll(globalBackup)
        }
    }

    @Test
    fun registerExtraExtractorsProducesNoDuplicateNameUrlPairs() {
        // Two extractors with the same (name, mainUrl) pair are functionally identical:
        // they'd handle the same URLs and report the same name. This test guards against
        // accidentally registering the same extractor twice.
        val globalBackup = com.lagradost.cloudstream3.utils.extractorApis.toList()
        com.lagradost.cloudstream3.utils.extractorApis.clear()
        try {
            com.lagradost.cloudstream3.extractors.registerExtraExtractors()
            val pairs = com.lagradost.cloudstream3.utils.extractorApis
                .map { it.name to it.mainUrl.lowercase().trimEnd('/') }
            val duplicatePairs = pairs.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue(
                "Duplicate (name, mainUrl) pairs in registerExtraExtractors: $duplicatePairs",
                duplicatePairs.isEmpty(),
            )
        } finally {
            com.lagradost.cloudstream3.utils.extractorApis.clear()
            com.lagradost.cloudstream3.utils.extractorApis.addAll(globalBackup)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Plugin load error message diagnostics
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun buildPluginErrorMessageHintsAtParCollectionsForAmapError() {
        val error = NoSuchMethodError(
            "No static method amap(Ljava/util/List;" +
                "Lkotlin/jvm/functions/Function2;" +
                "Lkotlin/coroutines/Continuation;)Ljava/lang/Object; " +
                "in class Lcom/lagradost/cloudstream3/ParCollectionsKt"
        )
        val msg = PluginRuntime.buildPluginErrorMessage(error, "/data/plugins/CloudPlay.cs3")
        assertTrue("Expected ParCollections hint in error message", msg.contains("ParCollectionsKt"))
        assertTrue("Expected plugin name in error message", msg.contains("CloudPlay"))
        assertTrue("Expected 'API mismatch' text", msg.contains("API mismatch"))
        assertTrue("Expected reinstall hint", msg.contains("reinstall"))
    }

    @Test
    fun buildPluginErrorMessageHintsAtCloudStreamApiForGenericNoSuchMethod() {
        val error = NoSuchMethodError(
            "No virtual method foo()V in class " +
                "Lcom/lagradost/cloudstream3/MainAPI;"
        )
        val msg = PluginRuntime.buildPluginErrorMessage(error, "/data/plugins/TestPlugin.cs3")
        assertTrue("Expected 'API mismatch' text", msg.contains("API mismatch"))
        assertTrue("Expected plugin name", msg.contains("TestPlugin"))
    }

    @Test
    fun buildPluginErrorMessageForGenericErrorDoesNotMentionApiMismatch() {
        val error = RuntimeException("Something went wrong")
        val msg = PluginRuntime.buildPluginErrorMessage(error, "/data/plugins/BrokenPlugin.cs3")
        assertTrue("Expected 'load failed' text", msg.contains("load failed"))
        assertTrue("Expected plugin name", msg.contains("BrokenPlugin"))
        assertFalse("Should not claim API mismatch for generic error", msg.contains("API mismatch"))
    }

    @Test
    fun buildPluginErrorMessageIncludesExceptionKind() {
        val error = IllegalStateException("bad state")
        val msg = PluginRuntime.buildPluginErrorMessage(error, "/data/plugins/MyPlugin.cs3")
        assertTrue(msg.contains("IllegalStateException"))
    }

    @Test
    fun buildPluginErrorMessageHandlesNullMessage() {
        // Some JVM errors have null messages; ensure we don't NPE
        val error = object : NoSuchMethodError() {
            override val message: String? = null
        }
        val msg = PluginRuntime.buildPluginErrorMessage(error, "/data/plugins/NullMsgPlugin.cs3")
        assertNotNull(msg)
        assertFalse(msg.isBlank())
    }
}
