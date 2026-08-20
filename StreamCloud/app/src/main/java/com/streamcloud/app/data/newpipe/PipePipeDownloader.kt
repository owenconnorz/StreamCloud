package com.streamcloud.app.data.newpipe

import dev.maxrave.pipepipe.extractor.downloader.CancellableCall
import dev.maxrave.pipepipe.extractor.downloader.Downloader
import dev.maxrave.pipepipe.extractor.downloader.Request as PipePipeRequest
import dev.maxrave.pipepipe.extractor.downloader.Response as PipePipeResponse
import dev.maxrave.pipepipe.extractor.exceptions.ReCaptchaException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network bridge for PipePipe's maintained extractor. It deliberately uses the same public web
 * profile for extraction and playback validation so a URL is not generated under one profile and
 * requested under another.
 */
class PipePipeDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    var ytMusicCookie: String = ""

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: PipePipeRequest): PipePipeResponse {
        client.newCall(buildRequest(request)).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("YouTube requested a CAPTCHA challenge", request.url())
            }
            return response.toPipePipeResponse()
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun executeAsync(
        request: PipePipeRequest,
        callback: AsyncCallback?,
    ): CancellableCall {
        val call = client.newCall(buildRequest(request))
        val cancellable = CancellableCall(call)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cancellable.setFinished()
                callback?.onError(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        if (it.code == 429) {
                            callback?.onError(
                                ReCaptchaException(
                                    "YouTube requested a CAPTCHA challenge",
                                    request.url(),
                                ),
                            )
                        } else {
                            callback?.onSuccess(it.toPipePipeResponse())
                        }
                    } catch (e: Exception) {
                        callback?.onError(e)
                    } finally {
                        cancellable.setFinished()
                    }
                }
            }
        })
        return cancellable
    }

    private fun buildRequest(request: PipePipeRequest): Request {
        val builder = Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .header("User-Agent", WEB_USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val host = request.url().toHttpUrlOrNull()?.host.orEmpty()
        if (ytMusicCookie.isNotBlank() && isYouTubeHost(host)) {
            builder.header("Cookie", ytMusicCookie)
        }
        return builder.build()
    }

    private fun okhttp3.Response.toPipePipeResponse(): PipePipeResponse {
        val rawBody = body?.bytes() ?: ByteArray(0)
        return PipePipeResponse(
            code,
            message,
            headers.toMultimap(),
            rawBody.toString(Charsets.UTF_8),
            rawBody,
            request.url.toString(),
        )
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com")

    companion object {
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val PLAYBACK_USER_AGENT =
            "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"

        val instance: PipePipeDownloader by lazy { PipePipeDownloader() }
    }
}