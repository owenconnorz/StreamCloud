package com.streamcloud.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPolicyTest {
    @Test
    fun currentConfigurationCannotRequestNetwork() {
        assertFalse(LiveAdPolicy.mayRequestNetwork(LiveAdPolicy.current))
    }

    @Test
    fun everyReadinessConditionIsRequiredAndDebugAlwaysBlocksLiveAds() {
        val ready = AdReadiness(true, true, true, false)
        assertTrue(LiveAdPolicy.mayRequestNetwork(ready))
        assertFalse(LiveAdPolicy.mayRequestNetwork(ready.copy(zoneSizeConfirmed = false)))
        assertFalse(LiveAdPolicy.mayRequestNetwork(ready.copy(cmpIntegrated = false)))
        assertFalse(LiveAdPolicy.mayRequestNetwork(ready.copy(hasCurrentConsent = false)))
        assertFalse(LiveAdPolicy.mayRequestNetwork(ready.copy(isDebugBuild = true)))
    }

    @Test
    fun externalClicksRequirePlainHttpsAuthority() {
        assertTrue(isSafeExternalClick("https://example.com/ad"))
        assertFalse(isSafeExternalClick("http://example.com/ad"))
        assertFalse(isSafeExternalClick("intent://example.com"))
        assertFalse(isSafeExternalClick("https://user:pass@example.com/ad"))
        assertFalse(isSafeExternalClick("https:///missing-host"))
    }

    @Test
    fun htmlContainsOnlyApprovedTagAndNoDynamicContext() {
        val html = buildExoClickHtml()
        assertTrue(html.contains("""class="${ExoClickTag.ELEMENT_CLASS}""""))
        assertTrue(html.contains("""data-zoneid="${ExoClickTag.ZONE_ID}""""))
        assertTrue(html.contains("""src="${ExoClickTag.SCRIPT_URL}""""))
        assertFalse(html.contains("placement"))
        assertFalse(html.contains("consent"))
        assertFalse(html.contains("referrer"))
    }
}