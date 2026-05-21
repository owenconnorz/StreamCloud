package com.streamcloud.app.data.util

fun String.hqYtThumb(target: Int = 720): String {
    if (isBlank()) return this



    val whRe = Regex("""=w(\d+)-h(\d+)""")
    if (whRe.containsMatchIn(this)) {
        val base = split("=w")[0]
        return "$base=w$target-h$target-p-l90-rj"
    }


    val ytImgRe = Regex("""(https?://i\.ytimg\.com/vi/[^/]+/)([a-zA-Z0-9]+)\.jpg""")
    ytImgRe.find(this)?.let { m ->
        val (_, prefix, _) = m.groupValues
        return "${prefix}hqdefault.jpg"
    }


    if (this matches Regex("""https://yt3\.ggpht\.com/.*=s\d+""")) {
        return "$this-s$target"
    }

    return this
}

fun String?.hqYtThumbOrNull(target: Int = 720): String? = this?.hqYtThumb(target)
