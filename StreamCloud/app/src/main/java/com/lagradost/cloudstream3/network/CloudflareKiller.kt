package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Compatibility type for extensions that attach CloudStream's interceptor to requests.
 *
 * This pass-through implementation does not attempt to solve or evade Cloudflare challenges.
 */
class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request())
}
