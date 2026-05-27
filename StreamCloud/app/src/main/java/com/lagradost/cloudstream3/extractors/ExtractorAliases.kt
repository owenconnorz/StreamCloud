@file:Suppress("unused", "MemberVisibilityCanBePrivate", "ClassName")
package com.lagradost.cloudstream3.extractors

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// =============================================================================
// CANONICAL NAME ALIASES
//
// Plugins compiled against the official CloudStream library reference extractors
// by their canonical "Extractor"-suffixed class names.  The host app must have
// these classes in its DEX (inherited by the plugin's DexClassLoader), otherwise
// the JVM throws NoClassDefFoundError at runtime.
//
// Every alias here is a real subclass — typealiases are compile-time only and
// would NOT fix runtime class resolution.
// =============================================================================

// ── StreamWish family ────────────────────────────────────────────────────────
open class StreamWishExtractor    : StreamWish()
open class StreamwishExtractor    : StreamWish()
open class EmbedWish              : StreamWish() { override val mainUrl = "https://embedwish.com";   override val name = "EmbedWish"    }
open class EmbedWishExtractor     : StreamWish() { override val mainUrl = "https://embedwish.com";   override val name = "EmbedWish"    }
open class AwishExtractor         : StreamWish() { override val mainUrl = "https://awish.pro";        override val name = "AWish"        }
open class WishembedExtractor     : StreamWish() { override val mainUrl = "https://wishembed.pro";    override val name = "Wishembed"    }
open class StreamRubyExtractor    : StreamWish() { override val mainUrl = "https://streamruby.com";   override val name = "StreamRuby"   }
open class WishOnlyExtractor      : StreamWish() { override val mainUrl = "https://wishonly.site";    override val name = "WishOnly"     }
open class StmRubyExtractor       : StreamWish() { override val mainUrl = "https://stmruby.com";      override val name = "StmRuby"      }

// ── FileMoon family ───────────────────────────────────────────────────────────
open class FileMoonExtractor      : FileMoon()
open class FilemoonExtractor      : FileMoon()
open class FilemoonSxExtractor    : FileMoon() { override val mainUrl = "https://filemoon.sx";         override val name = "FilemoonSx"   }
open class FilemoonInExtractor    : FileMoon() { override val mainUrl = "https://filemoon.in";         override val name = "FilemoonIn"   }
open class FilemoonXyzExtractor   : FileMoon() { override val mainUrl = "https://filemoon.xyz";        override val name = "FilemoonXyz"  }
open class KerapoxyExtractor      : FileMoon() { override val mainUrl = "https://kerapoxy.cc";         override val name = "Kerapoxy"     }
open class NetembedExtractor      : FileMoon() { override val mainUrl = "https://netembed.xyz";        override val name = "Netembed"     }
open class MoonPlayerExtractor    : FileMoon() { override val mainUrl = "https://moonplayer.sbs";      override val name = "MoonPlayer"   }

// ── Voe ───────────────────────────────────────────────────────────────────────
open class VoeExtractor           : Voe()

// ── DoodStream family ─────────────────────────────────────────────────────────
open class DoodExtractor          : DoodLaExtractor()
open class Doodla                 : DoodLaExtractor()
open class Dood                   : DoodLaExtractor()
open class DoodtoExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.to";      override val name = "DoodTo"       }
open class DoodsoExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.so";      override val name = "DoodSo"       }
open class DoodwatchExtractor     : DoodLaExtractor() { override val mainUrl = "https://dood.watch";   override val name = "DoodWatch"    }
open class DoodpmExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.pm";      override val name = "DoodPm"       }
open class DoodshExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.sh";      override val name = "DoodSh"       }
open class DoodsProExtractor      : DoodLaExtractor() { override val mainUrl = "https://doods.pro";    override val name = "DoodsPro"     }
open class Ds2PlayExtractor       : DoodLaExtractor() { override val mainUrl = "https://ds2play.com";  override val name = "Ds2Play"      }
open class DoooodExtractor        : DoodLaExtractor() { override val mainUrl = "https://dooood.com";   override val name = "Dooood"       }

// ── MixDrop ───────────────────────────────────────────────────────────────────
open class MixDropExtractor       : MixDrop()

// ── Mp4Upload ─────────────────────────────────────────────────────────────────
open class Mp4UploadExtractor     : Mp4Upload()

// ── StreamSB / SbPlay family ─────────────────────────────────────────────────
open class StreamSBExtractor      : StreamSB()
open class StreamSBapi            : StreamSB()
open class SBPlayExtractor        : StreamSB() { override val mainUrl = "https://sbplay.org";          override val name = "SbPlay"       }
open class SBPlay                 : StreamSB() { override val mainUrl = "https://sbplay.org";          override val name = "SbPlay"       }
open class SBPlay2                : StreamSB() { override val mainUrl = "https://sbplay2.com";         override val name = "SbPlay2"      }
open class CloudembExtractor      : StreamSB() { override val mainUrl = "https://cloudemb.com";        override val name = "CloudEmb"     }

// ── VidHide / FileLions family ────────────────────────────────────────────────
open class VidHideExtractor       : VidHide()
open class FileLionsExtractor     : VidHide() { override val mainUrl = "https://filelions.com";        override val name = "FileLions"    }
open class FileLionsToExtractor   : VidHide() { override val mainUrl = "https://filelions.to";         override val name = "FileLionsTo"  }
open class AnimeFeverExtractor    : VidHide() { override val mainUrl = "https://animefever.cc";        override val name = "AnimeFever"   }

// ── Vidmoly ───────────────────────────────────────────────────────────────────
open class VidmolyExtractor       : Vidmoly()

// ── Streamlare ────────────────────────────────────────────────────────────────
open class StreamlareExtractor    : Streamlare()

// ── Vtube ─────────────────────────────────────────────────────────────────────
open class VtubeExtractor         : Vtube()
open class VtbeToExtractor        : Vtube() { override val mainUrl = "https://vtbe.to";                override val name = "VtbeTo"       }

// ── VCloud ────────────────────────────────────────────────────────────────────
open class VCloudExtractor        : VCloud()
open class VcloudExtractor        : VCloud()

// ── StreamTape ────────────────────────────────────────────────────────────────
open class StreamTapeExtractor    : StreamTape()
open class StreamtapeExtractor    : StreamTape()

// ── Okru ──────────────────────────────────────────────────────────────────────
open class OkruExtractor          : Okru()
open class Okru2                  : Okru() { override val mainUrl = "https://odnoklassniki.ru";        override val name = "Okru2"        }

// ── SendVid ───────────────────────────────────────────────────────────────────
open class SendVidExtractor       : SendVid()
open class Sendvid                : SendVid()

// ── Kwik ──────────────────────────────────────────────────────────────────────
open class KwikExtractor          : Kwik()

// ── VidSrc (some plugins reference it without the "Extractor" suffix) ─────────
open class VidSrc                 : VidSrcExtractor()

// ── Superembed / 2embed ───────────────────────────────────────────────────────
open class SuperembedExtractor    : Superembed()
open class TwoEmbedExtractor      : Superembed() { override val mainUrl = "https://2embed.cc";         override val name = "2Embed"       }

// ── GeneralM3u8 ───────────────────────────────────────────────────────────────
open class GeneralM3u8ExtractorV2 : GeneralM3u8Extractor()
open class M3u8Manifest           : GeneralM3u8Extractor()
open class M3u8ManifestExtractor  : GeneralM3u8Extractor()

// =============================================================================
// NEW EXTRACTORS
// Widely-used hosters referenced by CloudStream plugins that were not yet
// present in the host app.  Registered by registerExtraExtractors() below.
// =============================================================================

private fun extractStream(html: String): String? {
    val unpacked = JsUnpacker(html).unpack() ?: html
    return Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(unpacked)?.groupValues?.get(1)
        ?: Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)
}

// ── Chillx ─── (anime/general) ───────────────────────────────────────────────
open class Chillx : ExtractorApi() {
    override val name = "Chillx"
    override val mainUrl = "https://chillx.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val b64 = Regex("""file\s*:\s*atob\(['"]([A-Za-z0-9+/=]+)['"]\)""").find(unpacked)?.groupValues?.get(1)
        val streamUrl: String? = if (b64 != null) {
            runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
        } else {
            extractStream(html)
        }
        if (streamUrl == null) return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class ChillxExtractor : Chillx()
open class Chillx2 : Chillx() { override val mainUrl = "https://player.chillx.top" }

// ── Vidguard ──────────────────────────────────────────────────────────────────
open class Vidguard : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class VidguardExtractor : Vidguard()
open class VidguardToExtractor : Vidguard() { override val mainUrl = "https://vidguard.to" }

// ── YourUpload ────────────────────────────────────────────────────────────────
open class YourUpload : ExtractorApi() {
    override val name = "YourUpload"
    override val mainUrl = "https://youruploud.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val streamUrl = Regex("""(?:file|src)\s*:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(?:file|src)\s*:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
        })
    }
}

open class YourUploadExtractor    : YourUpload()
open class YouruploadExtractor    : YourUpload() { override val mainUrl = "https://yourupload.com";    override val name = "YouruploadCom" }

// ── UpCloud ───────────────────────────────────────────────────────────────────
open class UpCloud : ExtractorApi() {
    override val name = "UpCloud"
    override val mainUrl = "https://vid.pro.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class UpCloudExtractor       : UpCloud()
open class UpcloudExtractor       : UpCloud()
open class VidProCoExtractor      : UpCloud()
open class UpCloudLink            : UpCloud() { override val mainUrl = "https://upcloud.link";         override val name = "UpCloudLink"  }

// ── GDrivePlayer ─────────────────────────────────────────────────────────────
open class GDrivePlayerExtractor : ExtractorApi() {
    override val name = "GDrivePlayer"
    override val mainUrl = "https://gdriveplayer.me"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

// ── Speedostream ──────────────────────────────────────────────────────────────
open class Speedostream : ExtractorApi() {
    override val name = "Speedostream"
    override val mainUrl = "https://speedostream.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class SpeedostreamExtractor  : Speedostream()

// ── WcoStream ─────────────────────────────────────────────────────────────────
open class WcoStream : ExtractorApi() {
    override val name = "WcoStream"
    override val mainUrl = "https://wcostream.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class WcoStreamExtractor     : WcoStream()

// ── Blogger JWPlayer ─────────────────────────────────────────────────────────
open class BloggerExtractor : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val videoUrl = Regex("""content=['"](https://[^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: extractStream(html)
            ?: return
        callback(newExtractorLink(name, name, videoUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class BloggerJWPlayerExtractor : BloggerExtractor()

// ── Mcloud ────────────────────────────────────────────────────────────────────
open class Mcloud : ExtractorApi() {
    override val name = "Mcloud"
    override val mainUrl = "https://mcloud.ag"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url
            quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class McloudExtractor : Mcloud()
open class McloudTo : Mcloud() { override val mainUrl = "https://mcloud.to"; override val name = "McloudTo" }

// ── XStreamCDN ───────────────────────────────────────────────────────────────
open class XStreamCDN : ExtractorApi() {
    override val name = "XStreamCDN"
    override val mainUrl = "https://www.xstreamcdn.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/(?:v|e|embed)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val apiUrl = "$mainUrl/api/source/$id"
        val resp = app.post(apiUrl, referer = referer ?: mainUrl,
            data = mapOf("r" to (referer ?: ""), "d" to mainUrl.substringAfter("//")),
        ).parsedSafe<Map<String, Any>>()
        val data = resp?.get("data") as? List<*> ?: run {
            val streamUrl = extractStream(app.get(url, referer = referer ?: mainUrl).text) ?: return
            callback(newExtractorLink(name, name, streamUrl) {
                this.referer = url; quality = Qualities.Unknown.value
                type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
            return
        }
        data.forEach { entry ->
            val e = entry as? Map<*, *> ?: return@forEach
            val file = e["file"] as? String ?: return@forEach
            val label = e["label"] as? String ?: "Auto"
            val q = when { label.contains("1080") -> Qualities.P1080.value; label.contains("720") -> Qualities.P720.value; label.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
            callback(newExtractorLink(name, "$name $label", file) {
                this.referer = url; quality = q
                type = if (file.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
        }
    }
}

open class XStreamCDNExtractor    : XStreamCDN()

// ── FEmbed ────────────────────────────────────────────────────────────────────
open class FEmbed : ExtractorApi() {
    override val name = "FEmbed"
    override val mainUrl = "https://fembed.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/(?:v|e)/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val resp = app.post("$mainUrl/api/source/$id", referer = referer ?: url)
            .parsedSafe<Map<String, Any>>()
        val data = resp?.get("data") as? List<*> ?: return
        data.forEach { entry ->
            val e = entry as? Map<*, *> ?: return@forEach
            val file = e["file"] as? String ?: return@forEach
            val label = e["label"] as? String ?: "Auto"
            val q = when { label.contains("1080") -> Qualities.P1080.value; label.contains("720") -> Qualities.P720.value; label.contains("480") -> Qualities.P480.value; else -> Qualities.Unknown.value }
            callback(newExtractorLink(name, "$name $label", file) { this.referer = url; quality = q })
        }
    }
}

open class FEmbedExtractor        : FEmbed()
open class Fembed                 : FEmbed()

// ── Lulustream ────────────────────────────────────────────────────────────────
open class Lulustream : ExtractorApi() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulustream.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class LulustreamExtractor    : Lulustream()

// ── Fastdrive ─────────────────────────────────────────────────────────────────
open class Fastdrive : ExtractorApi() {
    override val name = "Fastdrive"
    override val mainUrl = "https://fastdrive.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class FastdrivePro           : Fastdrive()
open class FastdriveExtractor     : Fastdrive()

// ── Streamhub ─────────────────────────────────────────────────────────────────
open class Streamhub : ExtractorApi() {
    override val name = "Streamhub"
    override val mainUrl = "https://streamhub.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class StreamhubExtractor     : Streamhub()

// ── Hstream ───────────────────────────────────────────────────────────────────
open class Hstream : ExtractorApi() {
    override val name = "Hstream"
    override val mainUrl = "https://hstream.moe"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class HstreamExtractor       : Hstream()

// ── Rumble ────────────────────────────────────────────────────────────────────
open class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val videoUrl = Regex(""""videoUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?:mp4|hls)\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: extractStream(html)
            ?: return
        callback(newExtractorLink(name, name, videoUrl) {
            this.referer = mainUrl; quality = Qualities.Unknown.value
            type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class RumbleExtractor        : Rumble()

// ── GoodStream ────────────────────────────────────────────────────────────────
open class GoodStream : ExtractorApi() {
    override val name = "GoodStream"
    override val mainUrl = "https://goodstream.org"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

open class GoodStreamExtractor    : GoodStream()

// ── Pixeldrain ───────────────────────────────────────────────────────────────
open class Pixeldrain : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/(?:u|l)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://pixeldrain.com/api/file/$id?download"
        callback(newExtractorLink(name, name, directUrl) {
            this.referer = mainUrl; quality = Qualities.Unknown.value
        })
    }
}

open class PixeldrainExtractor    : Pixeldrain()

// ── MultiQuality ─────────────────────────────────────────────────────────────
open class MultiQuality : ExtractorApi() {
    override val name = "MultiQuality"
    override val mainUrl = "https://multiembed.mov"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

// ── Hdtube ────────────────────────────────────────────────────────────────────
open class Hdtube : ExtractorApi() {
    override val name = "Hdtube"
    override val mainUrl = "https://hdtube.live"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStream(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value
            type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        })
    }
}

// =============================================================================
// Registration of new extractors (aliases share the parent's mainUrl so they
// don't need separate entries — plugins instantiate them directly by class name)
// =============================================================================

fun registerExtraExtractors() {
    com.lagradost.cloudstream3.utils.extractorApis.addAll(listOf(
        Chillx(),
        Chillx2(),
        Vidguard(),
        YourUpload(),
        YouruploadExtractor(),
        UpCloud(),
        UpCloudLink(),
        GDrivePlayerExtractor(),
        Speedostream(),
        WcoStream(),
        BloggerExtractor(),
        BloggerJWPlayerExtractor(),
        Mcloud(),
        McloudTo(),
        XStreamCDN(),
        FEmbed(),
        Lulustream(),
        Fastdrive(),
        Streamhub(),
        Hstream(),
        Rumble(),
        GoodStream(),
        Pixeldrain(),
        MultiQuality(),
        Hdtube(),
    ))
}
