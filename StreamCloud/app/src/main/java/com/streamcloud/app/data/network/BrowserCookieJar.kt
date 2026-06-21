package com.streamcloud.app.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

object BrowserCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { bucket[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.filter { it.matches(url) } ?: emptyList()

    fun setCookie(domain: String, name: String, value: String) {
        val cookie = Cookie.Builder()
            .domain(domain)
            .name(name)
            .value(value)
            .path("/")
            .build()
        store.getOrPut(domain) { ConcurrentHashMap() }[name] = cookie
    }

    fun hasCfClearance(host: String): Boolean =
        store[host]?.containsKey("cf_clearance") == true

    fun clear() = store.clear()
}
