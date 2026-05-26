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
 * Fetches YouTube's player JavaScript and extracts the nsig function used to descramble the
 * 'n' query parameter in CDN stream URLs.
 *
 * Without descrambling, YouTube's CDN returns HTTP 403 for every non-WEB client
 * (IOS, ANDROID, TVHTML5, etc.) even when the /player API call succeeds.  The 'n' value in
 * the returned URL is intentionally obfuscated; the CDN validates the transformed value before
 * serving bytes.  This class replicates what YouTube's own player does — the same approach used
 * by yt-dlp, NewPipe, and InnerTune/Metrolist.
 *
 * Usage:
 *   val workingUrl = YtNSigDescrambler.descrambleUrl(rawUrl)
 *
 * Thread-safe; the player JS is fetched once per 6 hours and cached in memory.
 */
object YtNSigDescrambler {

    private const val TAG = "YtNSigDescrambler"
    private const val CACHE_TTL_MS = 6L * 3600 * 1000   // refresh player JS every 6 h

    @Volatile private var nsigSnippet: String? = null    // JS function expression: function(a){...}
    @Volatile private var snippetFetchedAt: Long = 0L
    private val initMutex = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // ── Public API ────────────────────────────────────────────────────────────

    /** Pre-warm: fetch player JS in the background so the first track plays without extra delay. */
    suspend fun warmUp() = ensureReady()

    /**
     * Descramble the 'n' parameter in a YouTube CDN URL.
     * Returns the original URL unchanged if descrambling fails (URL will likely 403).
     */
    suspend fun descrambleUrl(url: String): String {
        val nEncoded = Regex("""[?&]n=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: return url   // no n-param — nothing to do

        ensureReady()
        val snippet = nsigSnippet ?: return url.also {
            AppLogger.w(TAG, "nsig snippet unavailable — stream URL may 403")
        }

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
        if (nsigSnippet != null && now - snippetFetchedAt < CACHE_TTL_MS) return
        initMutex.withLock {
            if (nsigSnippet != null && System.currentTimeMillis() - snippetFetchedAt < CACHE_TTL_MS) return
            try {
                val js = fetchPlayerJs() ?: run {
                    AppLogger.w(TAG, "Could not fetch YouTube player JS")
                    return
                }
                val snip = extractNsigSnippet(js) ?: run {
                    AppLogger.w(TAG, "Could not extract nsig function from player JS")
                    return
                }
                verifySnippet(snip)
                nsigSnippet = snip
                snippetFetchedAt = System.currentTimeMillis()
                AppLogger.i(TAG, "nsig function ready (${snip.length} chars)")
            } catch (e: Exception) {
                AppLogger.w(TAG, "nsig init error: ${e.message}")
            }
        }
    }

    /** Sanity-check: verify the snippet evaluates to a function, not a syntax error. */
    private suspend fun verifySnippet(snippet: String) {
        quickJs(Dispatchers.Default) {
            evaluate<Any?>("""(function(){ var f=($snippet); return typeof f; })()""")
        }
    }

    private suspend fun fetchPlayerJs(): String? = withContext(Dispatchers.IO) {
        // Step 1: Fetch YouTube's iframe_api to get the current player hash.
        // This is the same approach Metrolist uses — more reliable than scraping a watch page
        // because iframe_api is a stable, small endpoint that always contains the player URL.
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

        // The iframe_api response contains a line like:
        //   ytcfg.set({"PLAYER_JS_URL":"/s/player/HASH/player_ias.vflset/..."});
        // or simply references /player/HASH/ somewhere in the script.
        // Metrolist-identical pattern: \\?/ matches both '/' and '\/' (JS-escaped slashes)
        val playerHash = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]+)\\?/""")
            .find(iframeApi)?.groupValues?.get(1) ?: run {
            Log.w(TAG, "Player hash not found in iframe_api (body length=${iframeApi.length})")
            return@withContext null
        }

        // Step 2: Download the player JS directly by hash.
        val playerUrl = "https://www.youtube.com/s/player/$playerHash/player_ias.vflset/en_US/base.js"
        Log.d(TAG, "Player JS: $playerUrl")

        runCatching {
            http.newCall(
                Request.Builder()
                    .url(playerUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .build()
            ).execute().use { it.body?.string() }
        }.getOrElse { e ->
            Log.w(TAG, "Player JS download failed: ${e.message}")
            null
        }
    }

    /**
     * Extract the nsig function as a JavaScript expression that evaluates to a callable.
     *
     * Returns one of:
     *   - `function(a){ ... }`                            (self-contained)
     *   - `(function(){ var HELPER={...}; return function(a){...}; })()`  (with helper)
     */
    private fun extractNsigSnippet(playerJs: String): String? {

        // ── Step 1: find the nsig function name ───────────────────────────────
        val funcName: String =
            // Pattern A: direct call  …&&(b=FUNCNAME(b)
            Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]{2,})\(b\)""")
                .find(playerJs)?.groupValues?.get(1)
            // Pattern B: array-indexed  …&&(b=ARRNAME[IDX](b)
            ?: run {
                val m = Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]{2,})\[(\d+)\]\(b\)""")
                    .find(playerJs) ?: return null
                val arrName = m.groupValues[1]
                val idx     = m.groupValues[2].toIntOrNull() ?: 0
                Regex("""var ${Regex.escape(arrName)}=\[([a-zA-Z0-9$,\s]+)\]""")
                    .find(playerJs)?.groupValues?.get(1)
                    ?.split(",")?.getOrNull(idx)?.trim()
                    ?: return null
            }

        Log.d(TAG, "nsig function name: $funcName")

        // ── Step 2: extract function body via brace-depth matching ────────────
        val funcExpr = extractFunctionExpression(playerJs, funcName) ?: return null

        // ── Step 3: detect any external helper object the function references ─
        // Pattern: var c=HELPEROBJ; where HELPEROBJ is a multi-char identifier
        val externalRef = Regex("""var [a-z]=([A-Za-z0-9$]{2,})[,;]""")
            .find(funcExpr)?.groupValues?.get(1)

        val helperCode = if (externalRef != null) {
            extractHelperDef(playerJs, externalRef)?.plus(";") ?: ""
        } else ""

        // ── Step 4: assemble snippet ──────────────────────────────────────────
        return if (helperCode.isEmpty()) {
            funcExpr   // already self-contained function expression
        } else {
            // Wrap in IIFE so the helper is defined before the nsig function runs
            "(function(){$helperCode return $funcExpr;})()"
        }
    }

    /** Extract the `function(a){...}` expression for `FUNCNAME=function(a){...}`. */
    private fun extractFunctionExpression(playerJs: String, funcName: String): String? {
        val marker = "$funcName=function("
        val defStart = playerJs.indexOf(marker).takeIf { it >= 0 }
            ?: playerJs.indexOf("$funcName =function(").takeIf { it >= 0 }
            ?: return null

        // skip past "funcName=" to get to "function("
        val funcStart = defStart + funcName.length + 1
        val braceOpen = playerJs.indexOf('{', funcStart).takeIf { it >= 0 } ?: return null
        val end = matchingBrace(playerJs, braceOpen) ?: return null
        return playerJs.substring(funcStart, end + 1)   // "function(a){...}"
    }

    /** Extract `var NAME={...}` or `var NAME=[...]` from player JS. */
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
