@file:Suppress("DEPRECATION")

package com.streamcloud.app.player

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.streamcloud.app.ui.icons.AppIcons as Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamcloud.app.data.api.XHamsterRepository
import kotlinx.coroutines.delay
import org.json.JSONTokener

private const val XHAMSTER_CURRENT_STREAM_JS = """
    (function() {
      var videos = Array.from(document.querySelectorAll('video'));
      videos.sort(function(a, b) {
        return (b.videoWidth * b.videoHeight) - (a.videoWidth * a.videoHeight);
      });
      var video = videos[0];
      var supported = function(url) {
        return /^https:\/\//i.test(url || '') &&
          /\.(m3u8|mpd|mp4|m4v|webm)(?:[?#]|$)/i.test(url);
      };
      var direct = [];
      if (video) {
        direct.push(video.currentSrc || '', video.src || '', video.getAttribute('src') || '');
        Array.from(video.querySelectorAll('source')).forEach(function(source) {
          direct.push(source.src || source.getAttribute('src') || '');
        });
      }
      var directMatch = direct.find(supported);
      if (directMatch) return directMatch;
      var resources = performance.getEntriesByType('resource')
        .map(function(entry) { return entry.name || ''; })
        .filter(supported)
        .reverse();
      var manifest = resources.find(function(url) {
        return /\.(m3u8|mpd)(?:[?#]|$)/i.test(url);
      });
      return manifest || resources[resources.length - 1] || '';
    })();
"""

private const val XHAMSTER_START_PLAYBACK_JS = """
    (function() {
      var videos = Array.from(document.querySelectorAll('video'));
      videos.sort(function(a, b) {
        return (b.videoWidth * b.videoHeight) - (a.videoWidth * a.videoHeight);
      });
      var video = videos[0];
      if (!video) return 'no-video';
      try {
        var result = video.play();
        if (result && result.catch) result.catch(function() {});
        return 'started';
      } catch (ignored) {
        return 'failed';
      }
    })();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XHamsterPlayerScreen(
    videoId: String,
    title: String,
    onBack: () -> Unit,
    downloadWhenAvailable: Boolean = false,
) {
    val context = LocalContext.current
    val pageUrl = remember(videoId) {
        runCatching { XHamsterRepository.videoPageUrl(videoId) }.getOrNull()
    }
    var progress by remember(videoId) { mutableIntStateOf(0) }
    var loadError by remember(videoId) {
        mutableStateOf(if (pageUrl == null) "This XHamster video link is invalid." else null)
    }
    var nativeStream by remember(videoId) { mutableStateOf<XHamsterNativeStream?>(null) }
    var downloadAttempted by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(false)
    }
    var downloadStatus by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(
            if (downloadWhenAvailable) {
                "Start the official player below to check for a direct video download."
            } else {
                null
            },
        )
    }
    var startOfficialPlayback by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(!downloadWhenAvailable)
    }
    var playRequestSent by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(false)
    }
    var nativeResolveFailed by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(false)
    }
    var resolutionMessage by remember(videoId, downloadWhenAvailable) {
        mutableStateOf(
            if (downloadWhenAvailable) {
                "Preparing a direct download…"
            } else {
                "Preparing native playback…"
            },
        )
    }

    val webView = remember(videoId, pageUrl) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            importantForAccessibility =
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                userAgentString = WebSettings.getDefaultUserAgent(context)
                    .replace("; wv", "")
                    .replace(" Version/4.0", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url ?: return true
                    val blocked = request.isForMainFrame && !isTrustedXHamsterPage(target)
                    if (blocked) {
                        loadError =
                            "XHamster redirected to an unsupported page. Check sign-in or verification in Account settings."
                        nativeResolveFailed = true
                    }
                    return blocked
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    loadError = null
                    progress = 0
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loadError = "XHamster could not load this page. Check your connection and retry."
                        nativeResolveFailed = true
                    }
                }
            }
            pageUrl?.let(::loadUrl)
        }
    }

    BackHandler(enabled = nativeStream == null) {
        onBack()
    }

    LaunchedEffect(
        webView,
        pageUrl,
        nativeStream,
        downloadAttempted,
        nativeResolveFailed,
        startOfficialPlayback,
        playRequestSent,
    ) {
        if (
            pageUrl == null ||
            nativeStream != null ||
            downloadAttempted ||
            nativeResolveFailed
        ) return@LaunchedEffect
        var elapsedSeconds = 0
        var loadedSeconds = 0
        while (
            nativeStream == null &&
            !downloadAttempted &&
            !nativeResolveFailed &&
            elapsedSeconds < 90 &&
            loadedSeconds < 45
        ) {
            if (progress >= 95) {
                loadedSeconds += 1
                if (startOfficialPlayback && !playRequestSent) {
                    webView.evaluateJavascript(XHAMSTER_START_PLAYBACK_JS) { result ->
                        val started = runCatching {
                            JSONTokener(result.orEmpty()).nextValue() as? String
                        }.getOrNull() == "started"
                        playRequestSent = started
                    }
                }
                webView.evaluateJavascript(XHAMSTER_CURRENT_STREAM_JS) { encodedResult ->
                    if (nativeStream != null) return@evaluateJavascript
                    if (downloadAttempted || nativeResolveFailed) return@evaluateJavascript
                    val streamUrl = runCatching {
                        JSONTokener(encodedResult.orEmpty()).nextValue() as? String
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: return@evaluateJavascript
                    if (!isNativePlayableXHamsterStream(streamUrl)) {
                        return@evaluateJavascript
                    }

                    val headers = buildMap {
                        put(
                            "User-Agent",
                            WebSettings.getDefaultUserAgent(context)
                                .replace("; wv", "")
                                .replace(" Version/4.0", ""),
                        )
                        put("Referer", pageUrl.orEmpty())
                        val streamUri = Uri.parse(streamUrl)
                        if (isTrustedXHamsterPage(streamUri)) {
                            CookieManager.getInstance().getCookie(streamUrl)
                                ?.takeIf(String::isNotBlank)
                                ?.let { put("Cookie", it) }
                        }
                    }
                    if (downloadWhenAvailable) {
                        downloadAttempted = true
                        val extension = directDownloadExtension(streamUrl)
                        if (extension == null) {
                            downloadStatus = "XHamster exposed a stream, but not a direct MP4, M4V, or WebM file for download."
                            nativeResolveFailed = true
                            return@evaluateJavascript
                        }
                        val fileName = title.take(60)
                            .replace(Regex("[^\\w \\-]"), "_")
                            .trim()
                            .ifBlank { "xhamster-video" } + ".$extension"
                        runCatching {
                            val request = DownloadManager.Request(Uri.parse(streamUrl))
                                .setTitle(title.take(100))
                                .setDescription("Downloading from XHamster via StreamCloud")
                                .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                                )
                                .setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    "StreamCloud/$fileName",
                                )
                                .addRequestHeader("User-Agent", headers.getValue("User-Agent"))
                                .addRequestHeader("Referer", headers.getValue("Referer"))
                            val downloadManager = context.getSystemService(
                                android.content.Context.DOWNLOAD_SERVICE,
                            ) as DownloadManager
                            downloadManager.enqueue(request)
                        }.onSuccess {
                            downloadStatus = "Download started. Check your notifications for progress."
                        }.onFailure { error ->
                            downloadStatus = "Download failed: ${error.message ?: "unknown error"}"
                        }
                        return@evaluateJavascript
                    }
                    webView.onPause()
                    nativeStream = XHamsterNativeStream(streamUrl, headers)
                }
            }
            delay(1_000)
            elapsedSeconds += 1
        }
        if (
            nativeStream == null &&
            !downloadAttempted &&
            !nativeResolveFailed
        ) {
            if (downloadWhenAvailable && !startOfficialPlayback) {
                downloadStatus =
                    "No direct file was exposed yet. Start the official player below to check its stream."
            } else {
                nativeResolveFailed = true
                if (downloadWhenAvailable) {
                    downloadStatus =
                        "XHamster did not expose a direct video file for download."
                } else {
                    resolutionMessage =
                        "XHamster did not expose a stream that StreamCloud can play natively. Check provider sign-in or verification, then retry."
                }
            }
        }
    }
    LaunchedEffect(
        webView,
        nativeStream,
        downloadAttempted,
        nativeResolveFailed,
        startOfficialPlayback,
    ) {
        if (
            nativeStream == null &&
            !downloadAttempted &&
            !nativeResolveFailed
        ) {
            webView.onResume()
        } else {
            webView.onPause()
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    val activeNativeStream = nativeStream
    if (activeNativeStream != null) {
        NativePlayerScreen(
            streamUrl = activeNativeStream.url,
            title = title,
            headers = activeNativeStream.headers,
            onBack = onBack,
        )
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.01f),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101010))
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    loadError = null
                    nativeResolveFailed = false
                    downloadAttempted = false
                    startOfficialPlayback = !downloadWhenAvailable
                    playRequestSent = false
                    resolutionMessage = if (downloadWhenAvailable) {
                        "Preparing a direct download…"
                    } else {
                        "Preparing native playback…"
                    }
                    downloadStatus = if (downloadWhenAvailable) {
                        "Start the official player below to check for a direct video download."
                    } else {
                        null
                    }
                    progress = 0
                    webView.reload()
                }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry native playback",
                        tint = Color.White,
                    )
                }
            }
            if (
                !nativeResolveFailed &&
                !downloadAttempted &&
                loadError == null &&
                (!downloadWhenAvailable || startOfficialPlayback)
            ) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val message = loadError ?: downloadStatus ?: resolutionMessage
                Text(
                    text = message,
                    color = Color.White,
                )
                if (nativeResolveFailed || loadError != null || downloadAttempted) {
                    IconButton(onClick = {
                        loadError = null
                        nativeResolveFailed = false
                        downloadAttempted = false
                        startOfficialPlayback = !downloadWhenAvailable
                        playRequestSent = false
                        resolutionMessage = if (downloadWhenAvailable) {
                            "Preparing a direct download…"
                        } else {
                            "Preparing native playback…"
                        }
                        downloadStatus = if (downloadWhenAvailable) {
                            "Start the official player below to check for a direct video download."
                        } else {
                            null
                        }
                        progress = 0
                        webView.reload()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = Color.White,
                        )
                    }
                }
                if (
                    downloadWhenAvailable &&
                    !startOfficialPlayback &&
                    !downloadAttempted &&
                    !nativeResolveFailed
                ) {
                    Button(
                        onClick = {
                            startOfficialPlayback = true
                            playRequestSent = false
                            downloadStatus = "Starting the official player to locate a downloadable file…"
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start video to find download")
                    }
                }
            }
        }
    }
}

private fun isTrustedXHamsterPage(uri: Uri): Boolean {
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "xhamster.com" || host.endsWith(".xhamster.com"))
}

internal fun isNativePlayableXHamsterStream(rawUrl: String): Boolean {
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
    val path = uri.path.orEmpty().lowercase()
    return listOf(".m3u8", ".mp4", ".m4v", ".webm", ".mpd")
        .any(path::endsWith)
}

internal fun directDownloadExtension(rawUrl: String): String? {
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    val path = uri.path.orEmpty().lowercase()
    return listOf("mp4", "m4v", "webm").firstOrNull { path.endsWith(".$it") }
}

private data class XHamsterNativeStream(
    val url: String,
    val headers: Map<String, String>,
)
