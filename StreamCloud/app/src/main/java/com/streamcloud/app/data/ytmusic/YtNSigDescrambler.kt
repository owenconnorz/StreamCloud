package com.streamcloud.app.data.ytmusic

import android.util.Log
import com.dokar.quickjs.quickJs
import com.streamcloud.app.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches YouTube's player JavaScript and extracts:
 *  1. The nsig function for n-parameter descrambling (needed by web/TVHTML5 clients).
 *  2. The signatureTimestamp (sts) used by MOBILE, WEB_REMIX, WEB_CREATOR, TVHTML5, and
 *     ANDROID_CREATOR to get plain CDN URLs instead of cipher-only stream formats.
 *
 * Implementation mirrors Metrolist's PlayerJsFetcher + FunctionNameExtractor approach:
 * - iframe_api → player hash → player.js download
 * - Hardcoded fallback config for known player hashes (same table as Metrolist)
 * - Regex extraction with broader patterns matching Metrolist's FunctionNameExtractor
 */
object YtNSigDescrambler {

    private const val TAG = "YtNSigDescrambler"
    private const val CACHE_TTL_MS = 6L * 3600 * 1000   // refresh player JS every 6 h

    @Volatile private var nsigSnippet: String? = null
    @Volatile private var signatureTimestamp: Int? = null
    @Volatile private var currentPlayerHash: String? = null
    // snippetFetchedAt is set whenever we successfully download player JS, even when nsig
    // extraction fails (e.g. May 2026 player has no nsig function).  This prevents hammering
    // the player JS endpoint on every track resolution when the player has no nsig.
    @Volatile private var snippetFetchedAt: Long = 0L
    private val initMutex = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // ── Hardcoded player configs (identical to Metrolist's FunctionNameExtractor) ─────────
    // Keyed by the player JS hash extracted from iframe_api.
    // Used when regex extraction fails (e.g. Q-array obfuscated players or players with no
    // nsig/sig function at all like the May 2026 player).
    private data class HardcodedConfig(
        val sigFuncName: String,     // empty string = no sig cipher needed
        val nFuncName: String,       // empty string = no n-transform needed
        val signatureTimestamp: Int,
    )

    private val KNOWN_PLAYER_CONFIGS: Map<String, HardcodedConfig> = mapOf(
        // March 2026 player — Q-array obfuscated, has n-transform
        "74edf1a3" to HardcodedConfig(sigFuncName = "JI", nFuncName = "GU", signatureTimestamp = 20522),
        // April 2026 player
        "f4c47414" to HardcodedConfig(sigFuncName = "hJ", nFuncName = "",  signatureTimestamp = 20543),
        // May 2026 player — direct URLs, NO cipher, NO n-transform required
        "57f5d44f" to HardcodedConfig(sigFuncName = "",   nFuncName = "",  signatureTimestamp = 20591),
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Pre-warm: fetch player JS in the background so the first track plays without extra delay. */
    suspend fun warmUp() = ensureReady()

    /**
     * Return the signature timestamp (sts) from the current player JS.
     * Returns null if player JS has not been fetched yet.
     */
    fun getSignatureTimestamp(): Int? = signatureTimestamp

    /**
     * Descramble the 'n' parameter in a YouTube CDN URL.
     * Returns the original URL unchanged when descrambling fails.
     * For the May 2026 player the nsig function doesn't exist — no descramble is needed
     * and WEB/WEB_REMIX URLs are already valid without transformation.
     */
    suspend fun descrambleUrl(url: String): String {
        val nEncoded = Regex("""[?&]n=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: return url

        ensureReady()
        val snippet = nsigSnippet ?: return url  // no nsig for this player version — URL is fine as-is

        return try {
            val nDescrambled = quickJs(Dispatchers.Default) {
                evaluate<String?>(
                    """(function(){ var f=($snippet); return f("$nEncoded"); })()"""
                )
            } ?: return url.also { Log.w(TAG, "QuickJS returned null for n=$nEncoded") }

            url.replace(Regex("""([?&]n=)[^&]+"""), "$1$nDescrambled")
                .also { Log.d(TAG, "n-descrambled OK: $nEncoded → $nDescrambled") }
        } catch (e: Exception) {
            AppLogger.w(TAG, "n-descramble QuickJS failed: ${e.message}")
            url
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun ensureReady() {
        val now = System.currentTimeMillis()
        // Return early if we have already fetched player JS within the TTL window.
        // We use snippetFetchedAt (set on every successful player JS download) rather than
        // nsigSnippet (which stays null for players without an nsig function like 57f5d44f).
        // Without this fix, every MOBILE/WEB_REMIX track would re-download the player JS.
        if (snippetFetchedAt > 0 && now - snippetFetchedAt < CACHE_TTL_MS) return
        initMutex.withLock {
            if (snippetFetchedAt > 0 && System.currentTimeMillis() - snippetFetchedAt < CACHE_TTL_MS) return
            try {
                val (js, hash) = fetchPlayerJs() ?: run {
                    AppLogger.w(TAG, "Could not fetch YouTube player JS")
                    // Still update snippetFetchedAt so we don't hammer the network on every
                    // subsequent warmUp() call when the fetch consistently fails.
                    snippetFetchedAt = System.currentTimeMillis()
                    return
                }
                currentPlayerHash = hash
                Log.d(TAG, "Player JS fetched: hash=$hash (${js.length} chars)")

                // ── Signature timestamp ───────────────────────────────────────────
                // Always extract sts first — it's needed by MOBILE, WEB_REMIX, ANDROID_CREATOR etc.
                val sts = extractSignatureTimestamp(js, hash)
                if (sts != null) {
                    signatureTimestamp = sts
                    Log.d(TAG, "signatureTimestamp=$sts (hash=$hash)")
                } else {
                    AppLogger.w(TAG, "Could not extract signatureTimestamp (hash=$hash)")
                }

                // ── nsig function ─────────────────────────────────────────────────
                // Mark fetched NOW so TTL is respected even when nsig extraction fails.
                // Without this, repeated warmUp() calls hammer the player JS endpoint when
                // the player has no nsig function or when regex extraction fails.
                snippetFetchedAt = System.currentTimeMillis()

                // Check hardcoded config to see if this player TRULY has no n-transform.
                // A player TRULY has no n-transform only when BOTH sigFuncName AND nFuncName
                // are empty (like 57f5d44f / May 2026 player — direct URLs, no cipher at all).
                // f4c47414 has nFuncName="" meaning "extract via regex", NOT "no n-func".
                // Only skip extraction when we're certain this is a "direct URL" player.
                val hardcoded = KNOWN_PLAYER_CONFIGS[hash]
                val isDirectUrlPlayer = hardcoded != null
                    && hardcoded.sigFuncName.isEmpty()
                    && hardcoded.nFuncName.isEmpty()
                if (isDirectUrlPlayer) {
                    AppLogger.i(TAG, "Player $hash is a direct-URL player (no cipher, no n-transform)")
                    return
                }

                val snip = extractNsigSnippet(js) ?: run {
                    AppLogger.w(TAG, "nsig extraction failed for hash=$hash (n-transform unavailable)")
                    return
                }
                verifySnippet(snip)
                nsigSnippet = snip
                AppLogger.i(TAG, "nsig ready (${snip.length} chars), sts=$signatureTimestamp, hash=$hash")
            } catch (e: Exception) {
                AppLogger.w(TAG, "player JS init error: ${e.message}")
            }
        }
    }

    /**
     * Extract signatureTimestamp from player JS.
     * Mirrors Metrolist's FunctionNameExtractor.extractSignatureTimestamp():
     *  1. Try three regex patterns (broader than our original single pattern)
     *  2. Fall back to KNOWN_PLAYER_CONFIGS if regex fails
     */
    private fun extractSignatureTimestamp(playerJs: String, hash: String?): Int? {
        val patterns = listOf(
            Regex("""signatureTimestamp['":\s]+(\d+)"""),
            Regex("""sts['":\s]+(\d+)"""),
            Regex(""""signatureTimestamp"\s*:\s*(\d+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(playerJs)?.groupValues?.get(1)?.toIntOrNull()
            if (match != null) return match
        }
        // Hardcoded fallback — same table Metrolist uses in FunctionNameExtractor
        if (hash != null) {
            val sts = KNOWN_PLAYER_CONFIGS[hash]?.signatureTimestamp
            if (sts != null) {
                Log.d(TAG, "Using hardcoded signatureTimestamp=$sts for hash=$hash")
                return sts
            }
        }
        return null
    }

    /** Sanity-check: verify the snippet evaluates to a function. */
    private suspend fun verifySnippet(snippet: String) {
        quickJs(Dispatchers.Default) {
            evaluate<Any?>("""(function(){ var f=($snippet); return typeof f; })()""")
        }
    }

    /**
     * Fetch the current YouTube player JS.
     * Returns Pair(playerJsContent, playerHash) or null on failure.
     * Mirrors Metrolist's PlayerJsFetcher.getPlayerJs() — iframe_api → hash → player.js.
     */
    private suspend fun fetchPlayerJs(): Pair<String, String>? = withContext(Dispatchers.IO) {
        // Step 1: fetch iframe_api to get the current player hash
        val iframeApi = runCatching {
            http.newCall(
                Request.Builder()
                    .url("https://www.youtube.com/iframe_api")
                    .header("User-Agent", DESKTOP_UA)
                    .build()
            ).execute().use { it.body?.string() ?: "" }
        }.getOrElse { e ->
            Log.w(TAG, "iframe_api fetch failed: ${e.message}")
            return@withContext null
        }

        // Metrolist pattern (also handles backslash-escaped slashes in JS strings)
        val hash = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]+)\\?/""")
            .find(iframeApi)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "Player hash not found in iframe_api (body=${iframeApi.length} chars)")
            return@withContext null
        }

        // Step 2: download the player JS by hash
        val playerUrl = "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_US/base.js"
        Log.d(TAG, "Downloading player JS: $playerUrl")

        val js = runCatching {
            http.newCall(
                Request.Builder()
                    .url(playerUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .build()
            ).execute().use { it.body?.string() }
        }.getOrElse { e ->
            Log.w(TAG, "Player JS download failed: ${e.message}")
            null
        } ?: return@withContext null

        Pair(js, hash)
    }

    /**
     * Extract the nsig function as a JavaScript expression.
     * Mirrors Metrolist's regex patterns from FunctionNameExtractor.extractNFunctionInfo().
     */
    private fun extractNsigSnippet(playerJs: String): String? {
        // ── Step 1: find the nsig function name ───────────────────────────────
        // All 5 n-function patterns from Metrolist's FunctionNameExtractor (in order).
        // Pattern 1: .get("n"))&&(b=FUNC[IDX](VAR)  — canonical 2024 pattern
        // Pattern 2: .get("n"))&&(FUNC=VAR[IDX](FUNC)  — 2025+ variant
        // Pattern 3: .get("n");if(m){var M=n.match...  — April 2026 variant (no func capture)
        //            → falls through to pattern 4/5 for the actual function name
        // Pattern 4: String.fromCharCode(110) = 'n'  — obfuscated 'n' key
        // Pattern 5: enhanced_except_ sentinel  — function body marker
        val funcName: String =
            // Pattern 1 (canonical): .get("n"))&&(b=FUNC[IDX](b)
            run {
                val m = Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)""")
                    .find(playerJs) ?: return@run null
                val name = m.groupValues[1]
                val idx  = m.groupValues[2].toIntOrNull()
                if (idx != null) {
                    Regex("""var ${Regex.escape(name)}=\[([a-zA-Z0-9$,\s]+)\]""")
                        .find(playerJs)?.groupValues?.get(1)
                        ?.split(",")?.getOrNull(idx)?.trim() ?: name
                } else name
            }
            // Pattern 2 (2025+): .get("n"))&&(FUNC=VAR[IDX](FUNC)
            ?: run {
                val m = Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)""")
                    .find(playerJs) ?: return@run null
                val varName = m.groupValues[2]
                val idx     = m.groupValues[3].toIntOrNull()
                if (idx != null) {
                    Regex("""var ${Regex.escape(varName)}=\[([a-zA-Z0-9$,\s]+)\]""")
                        .find(playerJs)?.groupValues?.get(1)
                        ?.split(",")?.getOrNull(idx)?.trim() ?: return@run null
                } else varName
            }
            // Pattern 4 (obfuscated): String.fromCharCode(110) === 'n'
            ?: Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)""")
                .find(playerJs)?.groupValues?.get(1)
            // Pattern 5 (sentinel): enhanced_except_ inside function body
            ?: Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_""")
                .find(playerJs)?.groupValues?.get(1)
            ?: return null.also { AppLogger.w(TAG, "No n-func pattern matched player JS") }

        Log.d(TAG, "nsig function name: $funcName")

        // ── Step 2: extract function body ────────────────────────────────────
        val funcExpr = extractFunctionExpression(playerJs, funcName) ?: return null

        // ── Step 3: include any external helper ──────────────────────────────
        val externalRef = Regex("""var [a-z]=([A-Za-z0-9$]{2,})[,;]""")
            .find(funcExpr)?.groupValues?.get(1)
        val helperCode = if (externalRef != null) {
            extractHelperDef(playerJs, externalRef)?.plus(";") ?: ""
        } else ""

        return if (helperCode.isEmpty()) funcExpr
               else "(function(){$helperCode return $funcExpr;})()"
    }

    private fun extractFunctionExpression(playerJs: String, funcName: String): String? {
        val marker = "$funcName=function("
        val defStart = playerJs.indexOf(marker).takeIf { it >= 0 }
            ?: playerJs.indexOf("$funcName =function(").takeIf { it >= 0 }
            ?: return null
        val funcStart = defStart + funcName.length + 1
        val braceOpen = playerJs.indexOf('{', funcStart).takeIf { it >= 0 } ?: return null
        val end = matchingBrace(playerJs, braceOpen) ?: return null
        return playerJs.substring(funcStart, end + 1)
    }

    private fun extractHelperDef(playerJs: String, varName: String): String? {
        for ((prefix, isObj) in listOf(Pair("var $varName={", true), Pair("var $varName=[", false))) {
            val start = playerJs.indexOf(prefix).takeIf { it >= 0 } ?: continue
            val openIdx = playerJs.indexOf(if (isObj) '{' else '[', start).takeIf { it >= 0 } ?: continue
            val closeIdx = if (isObj) matchingBrace(playerJs, openIdx) else matchingBracket(playerJs, openIdx)
            if (closeIdx != null) return playerJs.substring(start, closeIdx + 1)
        }
        return null
    }

    private fun matchingBrace(js: String, open: Int): Int? {
        var depth = 0
        for (i in open until js.length) {
            when (js[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
        }
        return null
    }

    private fun matchingBracket(js: String, open: Int): Int? {
        var depth = 0
        for (i in open until js.length) {
            when (js[i]) { '[' -> depth++; ']' -> { depth--; if (depth == 0) return i } }
        }
        return null
    }
}
