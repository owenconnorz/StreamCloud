package com.streamcloud.app.data.ytmusic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicPremiumDetectorTest {
    @Test fun explicitPremiumEntitlementIsAccepted() {
        val response = Json.parseToJsonElement("""{"account":{"isPremiumSubscriber":true}}""")
        assertTrue(YtMusicPremiumDetector.isPremium(response))
    }

    @Test fun premiumLabelInActiveAccountHeaderIsAccepted() {
        val response = Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{"accountByline":{"simpleText":"YouTube Premium"}}}""")
        assertTrue(YtMusicPremiumDetector.isPremium(response))
    }

    @Test fun marketingUpsellDoesNotUnlockPremium() {
        val response = Json.parseToJsonElement("""{"menu":{"items":[{"text":"Get YouTube Premium"}]}}""")
        assertFalse(YtMusicPremiumDetector.isPremium(response))
    }

    @Test fun similarMarketingTextInsideAccountHeaderDoesNotUnlockPremium() {
        val response = Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"Alex Premium Fan"},"accountByline":{"simpleText":"Try YouTube Premium"}}}""")
        assertFalse(YtMusicPremiumDetector.isPremium(response))
    }
}
