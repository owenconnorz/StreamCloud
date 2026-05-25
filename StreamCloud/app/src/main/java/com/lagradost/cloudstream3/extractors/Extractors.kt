@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

// ─────────────────────────────────────────────────────────────────────────────
// Registration — call once at app startup
// ─────────────────────────────────────────────────────────────────────────────

fun registerAllExtractors() {
    extractorApis.addAll(listOf(
        // StreamTape family
        StreamTape(),
        object : StreamTape() { override val mainUrl = "https://streamtape.net" },
        object : StreamTape() { override val mainUrl = "https://streamtape.xyz" },
        object : StreamTape() { override val mainUrl = "https://streamtape.live" },
        object : StreamTape() { override val mainUrl = "https://streamtape.to" },
        object : StreamTape() { override val mainUrl = "https://streamtape.cc" },

        // DoodStream family
        DoodLaExtractor(),
        object : DoodLaExtractor() { override val mainUrl = "https://dood.pm"       ; override val name = "DoodPm" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.to"       ; override val name = "DoodTo" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.so"       ; override val name = "DoodSo" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.watch"    ; override val name = "DoodWatch" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.yt"       ; override val name = "DoodYt" },
        object : DoodLaExtractor() { override val mainUrl = "https://doodstream.com"; override val name = "Doodstream" },
        object : DoodLaExtractor() { override val mainUrl = "https://ds2play.com"   ; override val name = "Ds2Play" },
        object : DoodLaExtractor() { override val mainUrl = "https://ds2video.com"  ; override val name = "Ds2Video" },
        object : DoodLaExtractor() { override val mainUrl = "https://dooood.com"    ; override val name = "Dooood" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.re"       ; override val name = "DoodRe" },
        object : DoodLaExtractor() { override val mainUrl = "https://dood.sh"       ; override val name = "DoodSh" },
        object : DoodLaExtractor() { override val mainUrl = "https://doods.pro"     ; override val name = "DoodsPro" },

        // MixDrop family
        MixDrop(),
        object : MixDrop() { override val mainUrl = "https://mixdrop.to"  },
        object : MixDrop() { override val mainUrl = "https://mixdrop.ch"  },
        object : MixDrop() { override val mainUrl = "https://mixdrop.bz"  },
        object : MixDrop() { override val mainUrl = "https://mixdrop.ps"  },
        object : MixDrop() { override val mainUrl = "https://mixdroop.bz" },
        object : MixDrop() { override val mainUrl = "https://mixdroop.co" },
        object : MixDrop() { override val mainUrl = "https://mixdrop.ag"  },

        // Voe
        Voe(),
        object : Voe() { override val mainUrl = "https://voe.la"      },
        object : Voe() { override val mainUrl = "https://voe.wtf"     },
        object : Voe() { override val mainUrl = "https://voe.bar"     },
        object : Voe() { override val mainUrl = "https://voe.pub"     },
        object : Voe() { override val mainUrl = "https://voe.run"     },
        object : Voe() { override val mainUrl = "https://voe.cx"      },
        object : Voe() { override val mainUrl = "https://voe.ink"     },

        // FileMoon family
        FileMoon(),
        object : FileMoon() { override val mainUrl = "https://filemoon.to"   },
        object : FileMoon() { override val mainUrl = "https://filemoon.in"   },
        object : FileMoon() { override val mainUrl = "https://filemoon.xyz"  },
        object : FileMoon() { override val mainUrl = "https://moonplayer.sbs"},
        object : FileMoon() { override val mainUrl = "https://kerapoxy.cc"   },
        object : FileMoon() { override val mainUrl = "https://netembed.xyz"  },

        // StreamWish family
        StreamWish(),
        object : StreamWish() { override val mainUrl = "https://streamwish.to"   ; override val name = "StreamWishTo"  },
        object : StreamWish() { override val mainUrl = "https://streamwish.site" ; override val name = "StreamWishSite"},
        object : StreamWish() { override val mainUrl = "https://streamwish.pw"   ; override val name = "StreamWishPw"  },
        object : StreamWish() { override val mainUrl = "https://awish.pro"       ; override val name = "AWish"         },
        object : StreamWish() { override val mainUrl = "https://wishonly.site"   ; override val name = "WishOnly"      },
        object : StreamWish() { override val mainUrl = "https://stmruby.com"     ; override val name = "StmRuby"       },
        object : StreamWish() { override val mainUrl = "https://embedwish.com"   ; override val name = "EmbedWish"     },
        object : StreamWish() { override val mainUrl = "https://streamruby.com"  ; override val name = "StreamRuby"    },
        object : StreamWish() { override val mainUrl = "https://vidhidevip.com"  ; override val name = "VidHideVip"    },

        // Vidhide / FileLions family
        VidHide(),
        object : VidHide() { override val mainUrl = "https://vidhide.to"      ; override val name = "VidHideTo"     },
        object : VidHide() { override val mainUrl = "https://filelions.com"   ; override val name = "FileLions"     },
        object : VidHide() { override val mainUrl = "https://filelions.to"    ; override val name = "FileLionsTo"   },
        object : VidHide() { override val mainUrl = "https://filelions.live"  ; override val name = "FileLionsLive" },
        object : VidHide() { override val mainUrl = "https://filelions.online"; override val name = "FileLionsOnline"},
        object : VidHide() { override val mainUrl = "https://animefever.cc"   ; override val name = "AnimeFever"   },

        // Mp4Upload
        Mp4Upload(),

        // Uqload / UpStream family
        Uqload(),
        object : Uqload() { override val mainUrl = "https://uqload.com" ; override val name = "UqloadCom" },
        object : Uqload() { override val mainUrl = "https://uqload.io"  ; override val name = "UqloadIo"  },
        object : Uqload() { override val mainUrl = "https://upstream.to"; override val name = "UpStream"  },
        object : Uqload() { override val mainUrl = "https://uprot.net"  ; override val name = "UpRot"     },

        // Vidmoly
        Vidmoly(),
        object : Vidmoly() { override val mainUrl = "https://vidmoly.com"; override val name = "VidmolyCom" },

        // Streamlare
        Streamlare(),

        // Vtube
        Vtube(),
        object : Vtube() { override val mainUrl = "https://vtbe.to"    ; override val name = "VtbeTo"    },
        object : Vtube() { override val mainUrl = "https://vt.cdnpro.cc"; override val name = "VtCdnpro" },

        // VCloud
        VCloud(),

        // SBPlay / StreamSB family
        StreamSB(),
        object : StreamSB() { override val mainUrl = "https://sbplay.org"     ; override val name = "SbPlay"       },
        object : StreamSB() { override val mainUrl = "https://sbplay2.com"    ; override val name = "SbPlay2"      },
        object : StreamSB() { override val mainUrl = "https://sbvid.net"      ; override val name = "SbVid"        },
        object : StreamSB() { override val mainUrl = "https://sblongvu.com"   ; override val name = "SbLongVu"     },
        object : StreamSB() { override val mainUrl = "https://cloudemb.com"   ; override val name = "CloudEmb"     },
        object : StreamSB() { override val mainUrl = "https://sbthe.com"      ; override val name = "SbThe"        },
        object : StreamSB() { override val mainUrl = "https://sbchill.com"    ; override val name = "SbChill"      },
        object : StreamSB() { override val mainUrl = "https://sbface.com"     ; override val name = "SbFace"       },
        object : StreamSB() { override val mainUrl = "https://sblanh.com"     ; override val name = "SbLanh"       },
        object : StreamSB() { override val mainUrl = "https://sbanseh.com"    ; override val name = "SbAnseh"      },
        object : StreamSB() { override val mainUrl = "https://sbrity.com"     ; override val name = "SbRity"       },
        object : StreamSB() { override val mainUrl = "https://sbvier.com"     ; override val name = "SbVier"       },
        object : StreamSB() { override val mainUrl = "https://sbspeed.com"    ; override val name = "SbSpeed"      },

        // Kwik (anime)
        Kwik(),

        // VidSrc family (simple passthrough)
        VidSrcExtractor(),
        object : VidSrcExtractor() { override val mainUrl = "https://vidsrc.xyz" ; override val name = "VidSrcXyz"  },
        object : VidSrcExtractor() { override val mainUrl = "https://vidsrc.pm"  ; override val name = "VidSrcPm"   },
        object : VidSrcExtractor() { override val mainUrl = "https://vidsrc.in"  ; override val name = "VidSrcIn"   },
        object : VidSrcExtractor() { override val mainUrl = "https://vidsrc.net" ; override val name = "VidSrcNet"  },
        object : VidSrcExtractor() { override val mainUrl = "https://vidsrc.cc"  ; override val name = "VidSrcCc"   },

        // Superembed / generic jwplayer
        Superembed(),
        object : Superembed() { override val mainUrl = "https://2embed.cc"  ; override val name = "2Embed"    },
        object : Superembed() { override val mainUrl = "https://multiembed.mov"; override val name = "MultiEmbed"},

        // Okru
        Okru(),

        // SendVid
        SendVid(),

        // M3U8 / direct passthrough for any hlsplayer-style embed
        GeneralM3u8Extractor(),
    ))
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private val filesRegex = Regex("""(?:file|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
private val sourcesRegex = Regex("""sources\s*[=:]\s*\[?\s*\{[^}]*(?:file|src)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

private fun extractM3u8OrMp4(html: String): String? {
    filesRegex.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }?.let { return it }
    sourcesRegex.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }?.let { return it }
    return null
}

private fun inferType(url: String): ExtractorLinkType = when {
    url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
    url.contains(".mpd",  true) -> ExtractorLinkType.DASH
    else                        -> ExtractorLinkType.VIDEO
}

private fun randomToken(len: Int = 10): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..len).map { chars.random() }.joinToString("")
}

// ─────────────────────────────────────────────────────────────────────────────
// StreamTape
// ─────────────────────────────────────────────────────────────────────────────

open class StreamTape : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer).text
        // Pattern: innerHTML = '//streamtape.com/get_video?id=...' + ('suffix')
        val linkR = Regex("""innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'""")
        val m = linkR.find(html)
        val raw = if (m != null) {
            "https:" + m.groupValues[1] + m.groupValues[2].drop(1)
        } else {
            // Fallback: single innerHTML pattern
            val r2 = Regex("""getElementById\(['"]robotlink['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]""")
            val v = r2.find(html)?.groupValues?.get(1) ?: return
            "https:$v"
        }
        callback(newExtractorLink(name, name, raw) {
            this.referer = url
            quality = Qualities.Unknown.value
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DoodStream
// ─────────────────────────────────────────────────────────────────────────────

open class DoodLaExtractor : ExtractorApi() {
    override val name = "DoodLa"
    override val mainUrl = "https://dood.la"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = url.substringAfterLast("/").substringBefore("?")
        val embedUrl = "$mainUrl/e/$id"
        val html = app.get(embedUrl, referer = mainUrl).text
        val passPath = Regex("""\$\.get\(['"]([^'"]+pass_md5[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""pass_md5['"]\s*,\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""/pass_md5/[^\s'"<]+""").find(html)?.value
            ?: return
        val token = Regex("""token=([^&'"\s]+)""").find(html)?.groupValues?.get(1) ?: ""
        val expiry = System.currentTimeMillis() / 1000 + 300
        val baseUrl = app.get("$mainUrl$passPath", referer = embedUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
        if (baseUrl.isBlank()) return
        val streamUrl = baseUrl + randomToken(10) + "?token=$token&expiry=$expiry"
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = mainUrl
            quality = Qualities.Unknown.value
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MixDrop
// ─────────────────────────────────────────────────────────────────────────────

open class MixDrop : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val embedUrl = url.replace("/f/", "/e/").replace("/d/", "/e/")
        val html = app.get(embedUrl, referer = referer).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: return
        val streamUrl = if (wurl.startsWith("//")) "https:$wurl" else wurl
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = embedUrl
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Voe
// ─────────────────────────────────────────────────────────────────────────────

open class Voe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer).text
        // Try HLS first
        val hlsUrl = Regex(""""hls"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""'hls'\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: Regex("""hls\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (hlsUrl != null) {
            callback(newExtractorLink(name, name, hlsUrl) {
                this.referer = mainUrl
                quality = Qualities.Unknown.value
                type = ExtractorLinkType.M3U8
            })
            return
        }
        // Fallback mp4
        val mp4Url = Regex(""""mp4"\s*:\s*"([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1)
            ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, mp4Url) {
            this.referer = mainUrl
            quality = Qualities.Unknown.value
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FileMoon (packed JS)
// ─────────────────────────────────────────────────────────────────────────────

open class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = extractM3u8OrMp4(unpacked) ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StreamWish (JW-player style)
// ─────────────────────────────────────────────────────────────────────────────

open class StreamWish : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = extractM3u8OrMp4(unpacked) ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VidHide / FileLions
// ─────────────────────────────────────────────────────────────────────────────

open class VidHide : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = extractM3u8OrMp4(unpacked) ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mp4Upload
// ─────────────────────────────────────────────────────────────────────────────

open class Mp4Upload : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = Regex("""player\.src\s*\(\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: extractM3u8OrMp4(unpacked) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Uqload / UpStream
// ─────────────────────────────────────────────────────────────────────────────

open class Uqload : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.co"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Vidmoly
// ─────────────────────────────────────────────────────────────────────────────

open class Vidmoly : ExtractorApi() {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Streamlare
// ─────────────────────────────────────────────────────────────────────────────

open class Streamlare : ExtractorApi() {
    override val name = "Streamlare"
    override val mainUrl = "https://streamlare.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/[ve]/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val apiUrl = "$mainUrl/api/video/stream/get"
        val response = app.post(apiUrl, json = mapOf("id" to id), referer = referer ?: mainUrl).parsedSafe<Map<String, Any>>()
        val data = (response?.get("result") as? Map<*, *>)
        val streamUrl = (data?.get("360") ?: data?.get("480") ?: data?.get("720") ?: data?.get("1080")) as? String ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = mainUrl
            quality = Qualities.P720.value
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Vtube
// ─────────────────────────────────────────────────────────────────────────────

open class Vtube : ExtractorApi() {
    override val name = "Vtube"
    override val mainUrl = "https://vtube.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = extractM3u8OrMp4(unpacked) ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VCloud
// ─────────────────────────────────────────────────────────────────────────────

open class VCloud : ExtractorApi() {
    override val name = "VCloud"
    override val mainUrl = "https://vcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StreamSB / SBPlay family
// ─────────────────────────────────────────────────────────────────────────────

open class StreamSB : ExtractorApi() {
    override val name = "StreamSB"
    override val mainUrl = "https://streamsb.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/(?:e|v|d)/([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast("/").substringBefore("?").also { if (it.isBlank()) return }
        val hexId = id.map { it.code.toString(16) }.joinToString("")
        val apiUrl = "$mainUrl/sources43/${hexId}36e4534e4e36e4534e4e36e4534e4e"
        val response = app.get(apiUrl,
            headers = mapOf(
                "watchsb"   to "streamsb",
                "referer"   to (referer ?: mainUrl),
                "X-Requested-With" to "XMLHttpRequest",
            )
        ).parsedSafe<Map<String, Any>>()
        val streamData = (response?.get("stream_data") as? Map<*, *>) ?: return
        val m3u8 = streamData["file"] as? String ?: return
        callback(newExtractorLink(name, name, m3u8) {
            this.referer = mainUrl
            quality = Qualities.Unknown.value
            type = ExtractorLinkType.M3U8
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Kwik (anime sites like Animepahe)
// ─────────────────────────────────────────────────────────────────────────────

open class Kwik : ExtractorApi() {
    override val name = "Kwik"
    override val mainUrl = "https://kwik.cx"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: "https://animepahe.ru/").text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = Regex("""source\s*=\s*'([^']+)'""").find(unpacked)?.groupValues?.get(1)
            ?: extractM3u8OrMp4(unpacked) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VidSrc — simple embed redirector; extract any m3u8/mp4 from the page
// ─────────────────────────────────────────────────────────────────────────────

open class VidSrcExtractor : ExtractorApi() {
    override val name = "VidSrc"
    override val mainUrl = "https://vidsrc.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Superembed / 2embed — JW-player style
// ─────────────────────────────────────────────────────────────────────────────

open class Superembed : ExtractorApi() {
    override val name = "Superembed"
    override val mainUrl = "https://superembed.stream"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = extractM3u8OrMp4(unpacked) ?: extractM3u8OrMp4(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Okru (ok.ru)
// ─────────────────────────────────────────────────────────────────────────────

open class Okru : ExtractorApi() {
    override val name = "Okru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        // ok.ru stores stream data as JSON in data-options attribute
        val dataOptions = Regex("""data-options=['"]([\s\S]+?)['"]""").find(html)?.groupValues?.get(1)?.let {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } ?: return
        val streamUrl = Regex("""["'](?:hls|url)["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(dataOptions)?.groupValues?.get(1)
            ?: Regex("""["']url["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(dataOptions)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = mainUrl
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SendVid
// ─────────────────────────────────────────────────────────────────────────────

open class SendVid : ExtractorApi() {
    override val name = "SendVid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = Regex("""source\s+src=['"](https://[^'"]+\.mp4[^'"]*)""").find(html)?.groupValues?.get(1)
            ?: Regex("""source\s+src=['"](https://[^'"]+\.m3u8[^'"]*)""").find(html)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = inferType(streamUrl)
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GeneralM3u8Extractor — last-resort catch-all for any embed returning m3u8/mp4
// ─────────────────────────────────────────────────────────────────────────────

open class GeneralM3u8Extractor : ExtractorApi() {
    override val name = "GeneralM3u8"
    override val mainUrl = "https://m3u8.tv"  // never actually matched; registered last
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Direct m3u8 / mp4 URL — just pass it straight through
        if (url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".mpd", true)) {
            callback(newExtractorLink(name, name, url) {
                this.referer = referer ?: ""
                quality = Qualities.Unknown.value
                type = inferType(url)
            })
        }
    }
}
