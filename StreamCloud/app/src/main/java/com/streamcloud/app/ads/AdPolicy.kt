package com.streamcloud.app.ads

import com.streamcloud.app.BuildConfig
import java.net.URI

enum class AdPlacement {
    Movies,
    Adult,
}

internal object ExoClickTag {
    const val ZONE_ID = "6033416"
    const val ELEMENT_CLASS = "eas6a97888e10"
    const val SCRIPT_URL = "https://a.magsrv.com/ad-provider.js"
    const val PRIVACY_URL = "https://www.exoclick.com/privacy-policy/"
    const val WIDTH_DP = 300
    const val HEIGHT_DP = 100
}

internal data class AdReadiness(
    val zoneSizeConfirmed: Boolean,
    val cmpIntegrated: Boolean,
    val hasCurrentConsent: Boolean,
    val isDebugBuild: Boolean,
)

internal object LiveAdPolicy {
    /**
     * Every condition is deliberately required. In particular, a local preference can never
     * stand in for CMP consent.
     */
    fun mayRequestNetwork(readiness: AdReadiness): Boolean =
        readiness.zoneSizeConfirmed &&
            readiness.cmpIntegrated &&
            readiness.hasCurrentConsent &&
            !readiness.isDebugBuild

    val current = AdReadiness(
        // Do not activate by flipping constants: replace these with confirmed zone
        // configuration and verified, revocable CMP state transported to the ad WebView.
        zoneSizeConfirmed = false,
        cmpIntegrated = false,
        hasCurrentConsent = false,
        isDebugBuild = BuildConfig.DEBUG,
    )

    val pendingReasons = listOf(
        "The account-side zone size has not been confirmed as 300 × 100.",
        "A real CMP integration and current consent are not available.",
    )
}

internal fun isSafeExternalClick(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}

/** A fixed tag with no dynamic placement, browsing, profile, or consent inputs. */
internal fun buildExoClickHtml(): String = """
    <!doctype html>
    <html><head>
      <meta name="viewport" content="width=300,initial-scale=1,maximum-scale=1">
      <style>html,body{margin:0;padding:0;width:300px;height:100px;overflow:hidden}</style>
    </head><body>
      <script async type="application/javascript" src="${ExoClickTag.SCRIPT_URL}"></script>
      <ins class="${ExoClickTag.ELEMENT_CLASS}" data-zoneid="${ExoClickTag.ZONE_ID}"></ins>
      <script>(AdProvider=window.AdProvider||[]).push({"serve":{}});</script>
    </body></html>
""".trimIndent()