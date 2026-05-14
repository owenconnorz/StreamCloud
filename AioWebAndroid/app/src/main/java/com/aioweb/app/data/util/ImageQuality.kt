package com.aioweb.app.data.util

/**
 * High-quality YouTube / Google image URL upgrades — ported from Metrolist's
 * `String.resize` (`ui/utils/YouTubeUtils.kt`).
 *
 * NewPipe / YT Music return tiny `=w120-h120` thumbnails by default. This
 * rewrites them to `=w<W>-h<H>-p-l90-rj` so Coil pulls a sharp, full-res image
 * instead of a blurry stamp.
 *
 * Also handles `i.ytimg.com/vi/<id>/...jpg` → `maxresdefault.jpg` for
 * NewPipe's "trending music" home feed which uses the legacy CDN.
 */
fun String.hqYtThumb(target: Int = 720): String {
    if (isBlank()) return this

    // 1. lh3.googleusercontent.com / yt3.googleusercontent.com / yt3.ggpht.com
    //    pattern: ".../...=w120-h120-l90-rj"  →  "=w<target>-h<target>-p-l90-rj"
    val whRe = Regex("""=w(\d+)-h(\d+)""")
    if (whRe.containsMatchIn(this)) {
        val base = split("=w")[0]
        return "$base=w$target-h$target-p-l90-rj"
    }

    // 2. i.ytimg.com/vi/<videoId>/<size>.jpg → maxresdefault.jpg
    val ytImgRe = Regex("""(https?://i\.ytimg\.com/vi/[^/]+/)([a-zA-Z0-9]+)\.jpg""")
    ytImgRe.find(this)?.let { m ->
        val (_, prefix, _) = m.groupValues
        return "${prefix}hqdefault.jpg"
    }

    // 3. yt3.ggpht.com avatars (=s48 etc.) → upscale via -s suffix (Metrolist trick)
    if (this matches Regex("""https://yt3\.ggpht\.com/.*=s\d+""")) {
        return "$this-s$target"
    }

    return this
}

fun String?.hqYtThumbOrNull(target: Int = 720): String? = this?.hqYtThumb(target)
