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
open class DoodYtExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.yt";       override val name = "DoodYt"       }
open class DoodytExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.yt";       override val name = "DoodYt"       }
open class DoodReExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.re";       override val name = "DoodRe"       }
open class DoodreExtractor        : DoodLaExtractor() { override val mainUrl = "https://dood.re";       override val name = "DoodRe"       }
open class DoodStreamExtractor    : DoodLaExtractor() { override val mainUrl = "https://doodstream.com"; override val name = "Doodstream"  }
open class DoooodcomExtractor     : DoodLaExtractor() { override val mainUrl = "https://dooood.com";    override val name = "Dooood"       }

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
// Note: real CloudStream API spells the class "Vidhide" (lowercase h).
// Plugins compiled against it reference VidhideExtractor, not VidHideExtractor.
// We keep both casings so both old and new plugins resolve correctly.
open class VidHideExtractor       : VidHide()
open class VidhideExtractor       : VidHide()   // lowercase h — real CloudStream API name
open class Vidhide                : VidHide()   // base-class variant (lowercase h)
open class VidhideTo              : VidHide() { override val mainUrl = "https://vidhide.to";            override val name = "VidhideTo"    }
open class FileLionsExtractor     : VidHide() { override val mainUrl = "https://filelions.com";        override val name = "FileLions"    }
open class FileLionsToExtractor   : VidHide() { override val mainUrl = "https://filelions.to";         override val name = "FileLionsTo"  }
open class FilelionsExtractor     : VidHide() { override val mainUrl = "https://filelions.com";        override val name = "FileLions"    }
open class AnimeFeverExtractor    : VidHide() { override val mainUrl = "https://animefever.cc";        override val name = "AnimeFever"   }

// ── Vidmoly ───────────────────────────────────────────────────────────────────
open class VidmolyExtractor       : Vidmoly()
open class Vidmolyme              : Vidmoly() { override val mainUrl = "https://vidmoly.me";  override val name = "VidmolyMe"  }
open class VidmolymeExtractor     : Vidmoly() { override val mainUrl = "https://vidmoly.me";  override val name = "VidmolyMe"  }
open class VidmolyComExtractor    : Vidmoly() { override val mainUrl = "https://vidmoly.com"; override val name = "VidmolyCom" }
open class VidmolyToExtractor     : Vidmoly() { override val mainUrl = "https://vidmoly.to";  override val name = "VidmolyTo"  }

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
// BATCH 2: All remaining extractors from the official CloudStream3 library
// that plugins may reference by class name.  A NoClassDefFoundError is thrown
// at runtime if the class is missing from the host DEX, so every known class
// must exist here even as a generic stub.
// =============================================================================

private fun extractStreamGeneric(html: String): String? {
    val unpacked = JsUnpacker(html).unpack() ?: html
    return Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(unpacked)?.groupValues?.get(1)
        ?: Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv|webm)[^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)
}

private fun inferLinkType(url: String): ExtractorLinkType =
    if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
    else if (url.contains(".mpd", ignoreCase = true)) ExtractorLinkType.DASH
    else ExtractorLinkType.VIDEO

// Generic base used by most JWPlayer/JsUnpacker-style hosts
open class GenericJwExtractor(
    override val name: String,
    override val mainUrl: String,
    override val requiresReferer: Boolean = false,
) : ExtractorApi() {
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val streamUrl = extractStreamGeneric(html) ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = url; quality = Qualities.Unknown.value; type = inferLinkType(streamUrl)
        })
    }
}

// ── Emturbovid ────────────────────────────────────────────────────────────────
open class Emturbovid : GenericJwExtractor("Emturbovid", "https://emturbovid.com")
open class EmturbovidExtractor : Emturbovid()
open class EmturbovidCom : Emturbovid() { override val mainUrl = "https://www.emturbovid.com" }

// ── WatchSB (StreamSB variant) ────────────────────────────────────────────────
open class WatchSB : StreamSB() { override val mainUrl = "https://watchsb.com"; override val name = "WatchSB" }
open class WatchSBExtractor : WatchSB()

// ── Rabbitstream / Megacloud / Dokicloud ──────────────────────────────────────
open class Rabbitstream : GenericJwExtractor("Rabbitstream", "https://rabbitstream.net")
open class RabbitstreamExtractor : Rabbitstream()
open class Megacloud : GenericJwExtractor("Megacloud", "https://megacloud.tv")
open class MegacloudExtractor : Megacloud()
open class MegaCloud : Megacloud()
open class MegaCloudExtractor : Megacloud()
open class Dokicloud : GenericJwExtractor("Dokicloud", "https://dokicloud.one")
open class DokicloudExtractor : Dokicloud()

// ── VidStack ──────────────────────────────────────────────────────────────────
open class VidStack : GenericJwExtractor("VidStack", "https://vidstack.io")
open class VidstackExtractor : VidStack()
open class Vidstack : VidStack()
open class Server1uns : GenericJwExtractor("Server1uns", "https://server1.uns.bio")

// ── Maxstream ─────────────────────────────────────────────────────────────────
open class Maxstream : GenericJwExtractor("Maxstream", "https://maxstream.video")
open class MaxstreamExtractor : Maxstream()
open class MaxStream : Maxstream()
open class MaxStreamExtractor : Maxstream()

// ── Filesim family ────────────────────────────────────────────────────────────
open class Filesim : GenericJwExtractor("Filesim", "https://filesim.com")
open class FilesimExtractor : Filesim()
open class Multimoviesshg : GenericJwExtractor("Multimoviesshg", "https://multimoviesshg.com")
open class Guccihide : GenericJwExtractor("Guccihide", "https://guccihide.com")
open class Ahvsh : GenericJwExtractor("Ahvsh", "https://ahvsh.com")
open class Moviesm4u : GenericJwExtractor("Moviesm4u", "https://moviesm4u.com")
open class StreamhideTo : GenericJwExtractor("StreamhideTo", "https://streamhide.to")
open class StreamhideCom : GenericJwExtractor("StreamhideCom", "https://streamhide.com")
open class Alions : GenericJwExtractor("Alions", "https://alions.pro")
open class AlionsExtractor : Alions()

// ── VidHidePro family ─────────────────────────────────────────────────────────
// Real CloudStream API defines Pro1–Pro10+; plugins compiled against it reference
// all of them. Class must exist in DEX — plugin sets its own mainUrl at runtime.
open class VidHidePro  : VidHide()
open class VidHidePro1 : VidHide() { override val mainUrl = "https://filelions.live";    override val name = "VidHidePro1"  }
open class VidHidePro2 : VidHide() { override val mainUrl = "https://filelions.online";  override val name = "VidHidePro2"  }
open class VidHidePro3 : VidHide() { override val mainUrl = "https://vidhidehub.com";    override val name = "VidHidePro3"  }
open class VidHidePro4 : VidHide() { override val mainUrl = "https://vidhide.com";       override val name = "VidHidePro4"  }
open class VidHidePro5 : VidHide() { override val mainUrl = "https://vidhide.art";       override val name = "VidHidePro5"  }
open class VidHidePro6 : VidHide() { override val mainUrl = "https://vidhide.pro";       override val name = "VidHidePro6"  }
open class VidHidePro7 : VidHide() { override val mainUrl = "https://filelions.xyz";     override val name = "VidHidePro7"  }
open class VidHidePro8 : VidHide() { override val mainUrl = "https://vidhidevip.com";    override val name = "VidHidePro8"  }
open class VidHidePro9 : VidHide() { override val mainUrl = "https://filelions.net";     override val name = "VidHidePro9"  }
open class VidHidePro10: VidHide() { override val mainUrl = "https://vidhide.cc";        override val name = "VidHidePro10" }
open class VidHideHub  : VidHide() { override val mainUrl = "https://vidhidehub.com";    override val name = "VidHideHub"   }
open class Ryderjet    : VidHide() { override val mainUrl = "https://ryderjet.com";       override val name = "Ryderjet"     }
open class VidhideProExtractor : VidHidePro()
open class VidhidePro1Extractor: VidHidePro1()
open class VidhidePro2Extractor: VidHidePro2()
open class VidhidePro3Extractor: VidHidePro3()
open class VidhidePro4Extractor: VidHidePro4()
open class VidhidePro5Extractor: VidHidePro5()
open class VidhidePro6Extractor: VidHidePro6()

// ── Gdriveplayer family ───────────────────────────────────────────────────────
open class Gdriveplayerio  : GenericJwExtractor("GdriveplayerIo",  "https://gdriveplayer.io")
open class Gdriveplayerapi : GenericJwExtractor("GdriveplayerApi", "https://gdriveplayer.to")
open class Gdriveplayerapp : GenericJwExtractor("GdriveplayerApp", "https://gdriveplayer.app")
open class Gdriveplayerfun : GenericJwExtractor("GdriveplayerFun", "https://gdriveplayer.fun")
open class DatabaseGdrive  : GenericJwExtractor("DatabaseGdrive",  "https://databasegdriveplayer.co")
open class DatabaseGdrive2 : GenericJwExtractor("DatabaseGdrive2", "https://series.databasegdriveplayer.co")
open class GDriveplayer    : GenericJwExtractor("GDriveplayer",    "https://gdriveplayer.me")

// ── SibNet ────────────────────────────────────────────────────────────────────
open class SibNet : GenericJwExtractor("SibNet", "https://video.sibnet.ru")
open class SibNetExtractor : SibNet()

// ── Userload ──────────────────────────────────────────────────────────────────
open class Userload : GenericJwExtractor("Userload", "https://userload.co")
open class UserloadExtractor : Userload()

// ── Supervideo ────────────────────────────────────────────────────────────────
open class Supervideo : GenericJwExtractor("Supervideo", "https://supervideo.cc")
open class SupervideoExtractor : Supervideo()

// ── Streamup / Streamix / Vidara ──────────────────────────────────────────────
open class Streamup : GenericJwExtractor("Streamup", "https://streamup.to")
open class StreamupExtractor : Streamup()
open class Streamix : GenericJwExtractor("Streamix", "https://streamix.so")
open class Vidara   : GenericJwExtractor("Vidara",   "https://vidara.to")

// ── Hxfile family ─────────────────────────────────────────────────────────────
open class Hxfile      : GenericJwExtractor("Hxfile",      "https://hxfile.co")
open class HxfileExtractor : Hxfile()
open class Neonime7n   : GenericJwExtractor("Neonime7n",   "https://neonime.fun")
open class Neonime8n   : GenericJwExtractor("Neonime8n",   "https://8njctn.neonime.net")
open class KotakAnimeid: GenericJwExtractor("KotakAnimeid","https://nontonanimeid.bio")
open class Yufiles     : GenericJwExtractor("Yufiles",     "https://yufiles.com")
open class Aico        : GenericJwExtractor("Aico",        "https://aico.media")

// ── Vicloud ───────────────────────────────────────────────────────────────────
open class Vicloud : GenericJwExtractor("Vicloud", "https://vicloud.sbs")
open class VicloudExtractor : Vicloud()

// ── GamoVideo ─────────────────────────────────────────────────────────────────
open class GamoVideo : GenericJwExtractor("GamoVideo", "https://gamovideo.com")
open class GamoVideoExtractor : GamoVideo()
open class GamoVod : GamoVideo() { override val name = "GamoVod" }

// ── Vidsonic ──────────────────────────────────────────────────────────────────
open class Vidsonic : GenericJwExtractor("Vidsonic", "https://vidsonic.net")
open class VidsonicExtractor : Vidsonic()

// ── Vidoza / Videzz ───────────────────────────────────────────────────────────
open class Vidoza : GenericJwExtractor("Vidoza", "https://vidoza.net")
open class VidozaExtractor : Vidoza()
open class Videzz : GenericJwExtractor("Videzz", "https://videzz.net")
open class VidezzExtractor : Videzz()

// ── Vido ──────────────────────────────────────────────────────────────────────
open class Vido : GenericJwExtractor("Vido", "https://vido.lol")
open class VidoExtractor : Vido()

// ── Mvidoo ────────────────────────────────────────────────────────────────────
open class Mvidoo : GenericJwExtractor("Mvidoo", "https://mvidoo.com")
open class MvidooExtractor : Mvidoo()

// ── StreamoUpload ─────────────────────────────────────────────────────────────
open class StreamoUpload : GenericJwExtractor("StreamoUpload", "https://streamoupload.xyz")
open class StreamoUploadExtractor : StreamoUpload()

// ── Streamplay ────────────────────────────────────────────────────────────────
open class Streamplay : GenericJwExtractor("Streamplay", "https://streamplay.to")
open class StreamplayExtractor : Streamplay()

// ── StreamSilk ────────────────────────────────────────────────────────────────
open class StreamSilk : GenericJwExtractor("StreamSilk", "https://streamsilk.com")
open class StreamSilkExtractor : StreamSilk()

// ── JWPlayer family (various anime/misc hosters) ──────────────────────────────
open class Meownime   : GenericJwExtractor("Meownime",   "https://meownime.ltd")
open class VidNest    : GenericJwExtractor("VidNest",    "https://vidnest.xyz")
open class DesuOdchan : GenericJwExtractor("DesuOdchan", "https://desustream.me")
open class DesuArcg   : GenericJwExtractor("DesuArcg",   "https://desustream.me")
open class DesuDrive  : GenericJwExtractor("DesuDrive",  "https://desustream.me")
open class DesuOdvip  : GenericJwExtractor("DesuOdvip",  "https://desustream.me")

// ── HubCloud ──────────────────────────────────────────────────────────────────
open class HubCloud : GenericJwExtractor("HubCloud", "https://hubcloud.lol")
open class HubCloudExtractor : HubCloud()

// ── Linkbox ───────────────────────────────────────────────────────────────────
open class Linkbox : ExtractorApi() {
    override val name = "Linkbox"
    override val mainUrl = "https://www.linkbox.to"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""(?:id=|/file/)([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val apiResp = app.get("$mainUrl/api/file/detail?itemId=$id", referer = mainUrl).parsedSafe<Map<String, Any>>()
        val itemInfoMap = (apiResp?.get("data") as? Map<*, *>)?.get("itemInfo") as? Map<*, *>
        val fileUrl = itemInfoMap?.get("url") as? String ?: return
        callback(newExtractorLink(name, name, fileUrl) {
            this.referer = mainUrl; quality = Qualities.Unknown.value; type = inferLinkType(fileUrl)
        })
    }
}
open class LinkboxExtractor : Linkbox()

// ── Gofile ────────────────────────────────────────────────────────────────────
open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: return
        val token = app.post("$mainUrl/api/accounts", referer = mainUrl)
            .parsedSafe<Map<String, Any>>()
            ?.let { (it["data"] as? Map<*, *>)?.get("token") as? String } ?: return
        val fileData = app.get("$mainUrl/api/contents/$id?wt=4fd6sg89d7s6&cache=300",
            headers = mapOf("Authorization" to "Bearer $token"), referer = mainUrl)
            .parsedSafe<Map<String, Any>>()
        val contents = ((fileData?.get("data") as? Map<*, *>)?.get("contents") as? Map<*, *>) ?: return
        contents.values.forEach { fileEntry ->
            val entry = fileEntry as? Map<*, *> ?: return@forEach
            val link = entry["link"] as? String ?: return@forEach
            callback(newExtractorLink(name, name, link) {
                this.referer = mainUrl; quality = Qualities.Unknown.value; type = inferLinkType(link)
            })
        }
    }
}
open class GofileExtractor : Gofile()

// ── Krakenfiles ───────────────────────────────────────────────────────────────
open class Krakenfiles : GenericJwExtractor("Krakenfiles", "https://krakenfiles.com")
open class KrakenfilesExtractor : Krakenfiles()

// ── Mediafire ─────────────────────────────────────────────────────────────────
open class Mediafire : ExtractorApi() {
    override val name = "Mediafire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        val dlUrl = Regex("""href=["'](https://download\d*\.mediafire\.com/[^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""id=["']downloadButton["'][^>]*href=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, dlUrl) {
            this.referer = mainUrl; quality = Qualities.Unknown.value
        })
    }
}
open class MediafireExtractor : Mediafire()

// ── Dailymotion ───────────────────────────────────────────────────────────────
open class Dailymotion : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = Regex("""video/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val html = app.get(embedUrl, referer = referer ?: mainUrl).text
        val streamUrl = Regex(""""stream_url"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: Regex("""(?:file|src)\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, streamUrl) {
            this.referer = embedUrl; quality = Qualities.Unknown.value; type = inferLinkType(streamUrl)
        })
    }
}
open class DailymotionExtractor : Dailymotion()
open class Geodailymotion : Dailymotion() { override val mainUrl = "https://geo.dailymotion.com"; override val name = "Geodailymotion" }

// ── Embedgram ─────────────────────────────────────────────────────────────────
open class Embedgram : GenericJwExtractor("Embedgram", "https://embedgram.com")
open class EmbedgramExtractor : Embedgram()

// ── Filegram ──────────────────────────────────────────────────────────────────
open class Filegram : GenericJwExtractor("Filegram", "https://filegram.to")
open class FilegramExtractor : Filegram()

// ── Evoload ───────────────────────────────────────────────────────────────────
open class Evoload : GenericJwExtractor("Evoload", "https://evoload.io")
open class EvoloadExtractor : Evoload()
open class Evoload1 : Evoload()

// ── ByseSX family ─────────────────────────────────────────────────────────────
open class ByseSX       : GenericJwExtractor("ByseSX",       "https://bysesx.com")
open class ByseSXExtractor : ByseSX()
open class Bysezejataos : GenericJwExtractor("Bysezejataos", "https://bysezejataos.com")
open class ByseBuho     : GenericJwExtractor("ByseBuho",     "https://bysebuho.com")
open class ByseVepoin   : GenericJwExtractor("ByseVepoin",   "https://bysevepoin.com")
open class ByseQekaho   : GenericJwExtractor("ByseQekaho",   "https://byseqekaho.com")

// ── Up4Stream ─────────────────────────────────────────────────────────────────
open class Up4Stream : GenericJwExtractor("Up4Stream", "https://up4stream.com")
open class Up4StreamExtractor : Up4Stream()
open class Up4FunTop : Up4Stream() { override val mainUrl = "https://up4fun.top"; override val name = "Up4FunTop" }

// ── PlayLtXyz ─────────────────────────────────────────────────────────────────
open class PlayLtXyz : GenericJwExtractor("PlayLtXyz", "https://play.lt.xyz")
open class PlayLtXyzExtractor : PlayLtXyz()

// ── Acefile ───────────────────────────────────────────────────────────────────
open class Acefile : GenericJwExtractor("Acefile", "https://acefile.co")
open class AcefileExtractor : Acefile()

// ── GUpload ───────────────────────────────────────────────────────────────────
open class GUpload : GenericJwExtractor("GUpload", "https://gupload.me")
open class GUploadExtractor : GUpload()

// ── Vinovo ────────────────────────────────────────────────────────────────────
open class VinovoSi : GenericJwExtractor("VinovoSi", "https://vinovo.si")
open class VinovoTo : GenericJwExtractor("VinovoTo", "https://vinovo.to")
open class Vinovo   : VinovoSi()

// ── Zplayer / Streamhub2 ──────────────────────────────────────────────────────
open class Zplayer    : GenericJwExtractor("Zplayer",    "https://zplayer.live")
open class ZplayerV2  : GenericJwExtractor("ZplayerV2",  "https://v2.zplayer.live")
open class ZplayerExtractor : Zplayer()
open class Streamhub2 : GenericJwExtractor("Streamhub2", "https://streamhub.to")

// ── Cda ───────────────────────────────────────────────────────────────────────
open class Cda : GenericJwExtractor("Cda", "https://ebd.cda.pl")
open class CdaExtractor : Cda()

// ── GDMirrorbot / Techinmind ──────────────────────────────────────────────────
open class GDMirrorbot : GenericJwExtractor("GDMirrorbot", "https://gdmirrorbot.nl")
open class GDMirrorbotExtractor : GDMirrorbot()
open class Techinmind  : GenericJwExtractor("Techinmind",  "https://stream.techinmind.space")

// ── Fastream ──────────────────────────────────────────────────────────────────
open class Fastream : GenericJwExtractor("Fastream", "https://fastream.to")
open class FastreamExtractor : Fastream()

// ── VkExtractor ───────────────────────────────────────────────────────────────
open class VkExtractor : GenericJwExtractor("VkExtractor", "https://vkvideo.ru")

// ── GoodstreamExtractor (different from GoodStream) ──────────────────────────
open class GoodstreamExtractor : GenericJwExtractor("GoodstreamExtractor", "https://goodstream.uno")

// ── Jeniusplay ────────────────────────────────────────────────────────────────
open class Jeniusplay : GenericJwExtractor("Jeniusplay", "https://jeniusplay.com")
open class JeniusplayExtractor : Jeniusplay()

// ── Wibufile ──────────────────────────────────────────────────────────────────
open class Wibufile : GenericJwExtractor("Wibufile", "https://wibufile.com")
open class WibufileExtractor : Wibufile()

// ── StreamEmbed ───────────────────────────────────────────────────────────────
open class StreamEmbed : GenericJwExtractor("StreamEmbed", "https://watch.gxplayer.xyz")
open class StreamEmbedExtractor : StreamEmbed()

// ── ContentX ──────────────────────────────────────────────────────────────────
open class ContentX : GenericJwExtractor("ContentX", "https://contentx.me")
open class ContentXExtractor : ContentX()

// ── LuluStream extended family ────────────────────────────────────────────────
open class LuluStream   : Lulustream()
open class Lulustream1  : Lulustream() { override val mainUrl = "https://lulustream.com"; override val name = "Lulustream1" }
open class Lulustream2  : Lulustream() { override val mainUrl = "https://kinoger.pw";     override val name = "Lulustream2" }
open class Luluvdoo     : Lulustream() { override val mainUrl = "https://luluvdoo.com";   override val name = "Luluvdoo"    }

// ── PixelDrain extended ───────────────────────────────────────────────────────
open class PixelDrain    : Pixeldrain() { override val mainUrl = "https://pixeldrain.com"; override val name = "PixelDrain" }
open class PixelDrainDev : Pixeldrain() { override val mainUrl = "https://pixeldrain.dev"; override val name = "PixelDrainDev" }

// ── GenericM3U8 ───────────────────────────────────────────────────────────────
open class GenericM3U8 : GeneralM3u8Extractor()

// ── StreamVid ─────────────────────────────────────────────────────────────────
open class StreamVid : GenericJwExtractor("StreamVid", "https://streamvid.net")
open class StreamVidExtractor : StreamVid()

// ── EmbedRise ─────────────────────────────────────────────────────────────────
open class EmbedRise : GenericJwExtractor("EmbedRise", "https://embedrise.com")
open class EmbedRiseExtractor : EmbedRise()

// ── Guard ─────────────────────────────────────────────────────────────────────
open class Guard : GenericJwExtractor("Guard", "https://guard.to")
open class GuardExtractor : Guard()

// ── VidPlay ───────────────────────────────────────────────────────────────────
open class VidPlay : GenericJwExtractor("VidPlay", "https://vidplay.site")
open class VidPlayExtractor : VidPlay()
open class VidPlayOnline : VidPlay() { override val mainUrl = "https://vidplay.online"; override val name = "VidPlayOnline" }

// ── Sendcm ────────────────────────────────────────────────────────────────────
open class Sendcm : GenericJwExtractor("Sendcm", "https://send.cm")
open class SendcmExtractor : Sendcm()

// ── Dbgo ──────────────────────────────────────────────────────────────────────
open class Dbgo : StreamSB() { override val mainUrl = "https://dbgo.fun"; override val name = "Dbgo" }
open class DbgoExtractor : Dbgo()

// ── VidSrcTo ──────────────────────────────────────────────────────────────────
open class VidSrcTo : VidSrcExtractor() { override val mainUrl = "https://vidsrc.to"; override val name = "VidSrcTo" }
open class VidSrcToExtractor : VidSrcTo()

// ── Zoro/Aniwatch ─────────────────────────────────────────────────────────────
open class Zoro : GenericJwExtractor("Zoro", "https://megacloud.tv")
open class ZoroExtractor : Zoro()

// ── Videovard ─────────────────────────────────────────────────────────────────
open class Videovard : GenericJwExtractor("Videovard", "https://videovard.sx")
open class VideovardExtractor : Videovard()
open class VideoVard : Videovard()
open class VideoVardExtractor : Videovard()

// ── Shiro ─────────────────────────────────────────────────────────────────────
open class Shiro : GenericJwExtractor("Shiro", "https://shiro.is")
open class ShiroExtractor : Shiro()

// ── Smashystream ──────────────────────────────────────────────────────────────
open class SmashyStream : GenericJwExtractor("SmashyStream", "https://smashystream.com")
open class SmashyStreamExtractor : SmashyStream()

// ── Additional DoodStream family ──────────────────────────────────────────────
open class DoodCxExtractor  : DoodLaExtractor() { override val mainUrl = "https://dood.cx";  override val name = "DoodCx"  }
open class DoodBzExtractor  : DoodLaExtractor() { override val mainUrl = "https://dood.bz";  override val name = "DoodBz"  }
open class DoodNlExtractor  : DoodLaExtractor() { override val mainUrl = "https://dood.nl";  override val name = "DoodNl"  }
open class DoodWsExtractor  : DoodLaExtractor() { override val mainUrl = "https://dood.ws";  override val name = "DoodWs"  }
open class Ds2VideoExtractor: DoodLaExtractor() { override val mainUrl = "https://ds2video.com"; override val name = "Ds2Video" }

// ── Additional StreamSB family ────────────────────────────────────────────────
open class SBVidExtractor    : StreamSB() { override val mainUrl = "https://sbvid.net";      override val name = "SbVid"    }
open class SBfull            : StreamSB() { override val mainUrl = "https://sbfull.com";     override val name = "SbFull"   }
open class SBfastExtractor   : StreamSB() { override val mainUrl = "https://sbfast.com";     override val name = "SbFast"   }

// ── Vixcloud ──────────────────────────────────────────────────────────────────
open class Vixcloud : GenericJwExtractor("Vixcloud", "https://vixcloud.co")
open class VixcloudExtractor : Vixcloud()

// ── Gcloud ────────────────────────────────────────────────────────────────────
open class Gcloud : GenericJwExtractor("Gcloud", "https://gcloud.live")
open class GcloudExtractor : Gcloud()

// ── VidBom / Hydrax ───────────────────────────────────────────────────────────
open class VidBom : GenericJwExtractor("VidBom", "https://vidbom.com")
open class VidBomExtractor : VidBom()
open class Hydrax : GenericJwExtractor("Hydrax", "https://hydrax.net")
open class HydraxExtractor : Hydrax()

// ── PlayerVdo ─────────────────────────────────────────────────────────────────
open class PlayerVdo : GenericJwExtractor("PlayerVdo", "https://playervdo.xyz")
open class PlayerVdoExtractor : PlayerVdo()

// ── FlixZaa ───────────────────────────────────────────────────────────────────
open class FlixZaa : GenericJwExtractor("FlixZaa", "https://flixzaa.com")
open class FlixZaaExtractor : FlixZaa()

// ── Jihocloud ─────────────────────────────────────────────────────────────────
open class Jihocloud : GenericJwExtractor("Jihocloud", "https://jihocloud.com")
open class JihocloudExtractor : Jihocloud()

// ── StreamEmbed / Gxplayer ────────────────────────────────────────────────────
open class Gxplayer : GenericJwExtractor("Gxplayer", "https://watch.gxplayer.xyz")
open class GxplayerExtractor : Gxplayer()

// ── Upstream (StreamSB variant) ───────────────────────────────────────────────
open class Upstream : StreamSB() { override val mainUrl = "https://upstream.to"; override val name = "Upstream" }
open class UpstreamExtractor : Upstream()

// ── MoVideo ───────────────────────────────────────────────────────────────────
open class MoVideo : GenericJwExtractor("MoVideo", "https://movideo.me")
open class MoVideoExtractor : MoVideo()

// ── Streamlare extended ───────────────────────────────────────────────────────
open class StreamlareCom : Streamlare() { override val mainUrl = "https://streamlare.com"; override val name = "StreamlareCom" }

// ── WcoStream / Wcostream aliases ─────────────────────────────────────────────
open class Wcostream : WcoStream()
open class WcostreamExtractor : WcoStream()

// =============================================================================
// COMPREHENSIVE NO-SUFFIX VARIANTS
// Plugins compiled against the real CloudStream API often reference class names
// WITHOUT the "Extractor" suffix (or with different casing).  We keep both so
// every plugin combination resolves correctly.
// =============================================================================

// ── StreamWish no-suffix / casing variants ───────────────────────────────────
open class Awish           : StreamWish() { override val mainUrl = "https://awish.pro";        override val name = "AWish"          }
open class WishOnly        : StreamWish() { override val mainUrl = "https://wishonly.site";    override val name = "WishOnly"       }
open class StmRuby         : StreamWish() { override val mainUrl = "https://stmruby.com";      override val name = "StmRuby"        }
open class StreamRuby      : StreamWish() { override val mainUrl = "https://streamruby.com";   override val name = "StreamRuby"     }
open class Wishembed       : StreamWish() { override val mainUrl = "https://wishembed.pro";    override val name = "Wishembed"      }
open class VidHideVip      : StreamWish() { override val mainUrl = "https://vidhidevip.com";   override val name = "VidHideVip"     }
open class VidhideVip      : StreamWish() { override val mainUrl = "https://vidhidevip.com";   override val name = "VidhideVip"     }
open class StreamWishTo    : StreamWish() { override val mainUrl = "https://streamwish.to";    override val name = "StreamWishTo"   }
open class StreamWishSite  : StreamWish() { override val mainUrl = "https://streamwish.site";  override val name = "StreamWishSite" }
open class StreamWishPw    : StreamWish() { override val mainUrl = "https://streamwish.pw";    override val name = "StreamWishPw"   }
open class StreamWishCom   : StreamWish() { override val mainUrl = "https://streamwish.com";   override val name = "StreamWishCom"  }
open class Streamwish      : StreamWish()

// ── FileMoon no-suffix / casing variants ─────────────────────────────────────
open class Filemoon        : FileMoon()
open class FilemoonSx      : FileMoon() { override val mainUrl = "https://filemoon.sx";        override val name = "FilemoonSx"     }
open class FileMoonSx      : FileMoon() { override val mainUrl = "https://filemoon.sx";        override val name = "FileMoonSx"     }
open class FilemoonIn      : FileMoon() { override val mainUrl = "https://filemoon.in";        override val name = "FilemoonIn"     }
open class FilemoonXyz     : FileMoon() { override val mainUrl = "https://filemoon.xyz";       override val name = "FilemoonXyz"    }
open class Kerapoxy        : FileMoon() { override val mainUrl = "https://kerapoxy.cc";        override val name = "Kerapoxy"       }
open class Netembed        : FileMoon() { override val mainUrl = "https://netembed.xyz";       override val name = "Netembed"       }
open class MoonPlayer      : FileMoon() { override val mainUrl = "https://moonplayer.sbs";     override val name = "MoonPlayer"     }

// ── DoodStream no-suffix / casing variants ───────────────────────────────────
open class DoodLa          : DoodLaExtractor()
open class DoodTo          : DoodLaExtractor() { override val mainUrl = "https://dood.to";     override val name = "DoodTo"         }
open class DoodSo          : DoodLaExtractor() { override val mainUrl = "https://dood.so";     override val name = "DoodSo"         }
open class DoodWatch       : DoodLaExtractor() { override val mainUrl = "https://dood.watch";  override val name = "DoodWatch"      }
open class DoodPm          : DoodLaExtractor() { override val mainUrl = "https://dood.pm";     override val name = "DoodPm"         }
open class DoodSh          : DoodLaExtractor() { override val mainUrl = "https://dood.sh";     override val name = "DoodSh"         }
open class DoodsPro        : DoodLaExtractor() { override val mainUrl = "https://doods.pro";   override val name = "DoodsPro"       }
open class DoodYt          : DoodLaExtractor() { override val mainUrl = "https://dood.yt";     override val name = "DoodYt"         }
open class DoodRe          : DoodLaExtractor() { override val mainUrl = "https://dood.re";     override val name = "DoodRe"         }
open class DoodCx          : DoodLaExtractor() { override val mainUrl = "https://dood.cx";     override val name = "DoodCx"         }
open class DoodBz          : DoodLaExtractor() { override val mainUrl = "https://dood.bz";     override val name = "DoodBz"         }
open class DoodNl          : DoodLaExtractor() { override val mainUrl = "https://dood.nl";     override val name = "DoodNl"         }
open class DoodWs          : DoodLaExtractor() { override val mainUrl = "https://dood.ws";     override val name = "DoodWs"         }
open class Ds2Play         : DoodLaExtractor() { override val mainUrl = "https://ds2play.com"; override val name = "Ds2Play"        }
open class Ds2Video        : DoodLaExtractor() { override val mainUrl = "https://ds2video.com";override val name = "Ds2Video"       }
open class Doodstream      : DoodLaExtractor() { override val mainUrl = "https://doodstream.com"; override val name = "Doodstream" }
open class Dooood          : DoodLaExtractor() { override val mainUrl = "https://dooood.com";  override val name = "Dooood"         }

// ── VidHide / FileLions no-suffix variants ───────────────────────────────────
open class FileLions       : VidHide() { override val mainUrl = "https://filelions.com";       override val name = "FileLions"      }
open class FileLionsTo     : VidHide() { override val mainUrl = "https://filelions.to";        override val name = "FileLionsTo"    }
open class FileLionsLive   : VidHide() { override val mainUrl = "https://filelions.live";      override val name = "FileLionsLive"  }
open class FileLionsOnline : VidHide() { override val mainUrl = "https://filelions.online";    override val name = "FileLionsOnline"}
open class AnimeFever      : VidHide() { override val mainUrl = "https://animefever.cc";       override val name = "AnimeFever"     }
open class VidHideTo       : VidHide() { override val mainUrl = "https://vidhide.to";          override val name = "VidHideTo"      }

// ── Vidmoly no-suffix variants ───────────────────────────────────────────────
open class VidmolyCom      : Vidmoly() { override val mainUrl = "https://vidmoly.com";         override val name = "VidmolyCom"     }
open class VidmolyTo       : Vidmoly() { override val mainUrl = "https://vidmoly.to";          override val name = "VidmolyTo"      }
open class Vidmolyto       : Vidmoly() { override val mainUrl = "https://vidmoly.to";          override val name = "VidmolyTo"      }
open class Vidmolycom      : Vidmoly() { override val mainUrl = "https://vidmoly.com";         override val name = "VidmolyCom"     }

// ── MixDrop no-suffix / casing variants ──────────────────────────────────────
open class Mixdrop         : MixDrop()
open class Mixdroop        : MixDrop() { override val mainUrl = "https://mixdroop.bz"; override val name = "Mixdroop" }

// ── StreamSB numbered variants (real CloudStream API names them StreamSB1-N) ──
// Plugin compiled against real API references StreamSB1..StreamSB8+ by name.
open class StreamSB1       : StreamSB() { override val mainUrl = "https://sbplay.org";    override val name = "StreamSB1"  }
open class StreamSB2       : StreamSB() { override val mainUrl = "https://sbplay2.com";   override val name = "StreamSB2"  }
open class StreamSB3       : StreamSB() { override val mainUrl = "https://sbvid.net";     override val name = "StreamSB3"  }
open class StreamSB4       : StreamSB() { override val mainUrl = "https://sblongvu.com";  override val name = "StreamSB4"  }
open class StreamSB5       : StreamSB() { override val mainUrl = "https://cloudemb.com";  override val name = "StreamSB5"  }
open class StreamSB6       : StreamSB() { override val mainUrl = "https://sbthe.com";     override val name = "StreamSB6"  }
open class StreamSB7       : StreamSB() { override val mainUrl = "https://sbchill.com";   override val name = "StreamSB7"  }
open class StreamSB8       : StreamSB() { override val mainUrl = "https://sbface.com";    override val name = "StreamSB8"  }
open class StreamSB9       : StreamSB() { override val mainUrl = "https://sblanh.com";    override val name = "StreamSB9"  }
open class StreamSB10      : StreamSB() { override val mainUrl = "https://sbanseh.com";   override val name = "StreamSB10" }
open class StreamSB11      : StreamSB() { override val mainUrl = "https://sbrity.com";    override val name = "StreamSB11" }
open class StreamSB12      : StreamSB() { override val mainUrl = "https://sbvier.com";    override val name = "StreamSB12" }
open class StreamSB13      : StreamSB() { override val mainUrl = "https://sbspeed.com";   override val name = "StreamSB13" }

// ── StreamSB no-suffix / named variants ───────────────────────────────────────
open class Cloudemb        : StreamSB() { override val mainUrl = "https://cloudemb.com"; override val name = "CloudEmb" }
open class Sbplay          : StreamSB() { override val mainUrl = "https://sbplay.org";   override val name = "SbPlay"   }
open class Sbplay2         : StreamSB() { override val mainUrl = "https://sbplay2.com";  override val name = "SbPlay2"  }
open class SbVid           : StreamSB() { override val mainUrl = "https://sbvid.net";    override val name = "SbVid"    }
open class SbLongVu        : StreamSB() { override val mainUrl = "https://sblongvu.com"; override val name = "SbLongVu" }
open class SbThe           : StreamSB() { override val mainUrl = "https://sbthe.com";    override val name = "SbThe"    }
open class SbChill         : StreamSB() { override val mainUrl = "https://sbchill.com";  override val name = "SbChill"  }
open class SbFace          : StreamSB() { override val mainUrl = "https://sbface.com";   override val name = "SbFace"   }
open class SbLanh          : StreamSB() { override val mainUrl = "https://sblanh.com";   override val name = "SbLanh"   }
open class SbAnseh         : StreamSB() { override val mainUrl = "https://sbanseh.com";  override val name = "SbAnseh"  }
open class SbRity          : StreamSB() { override val mainUrl = "https://sbrity.com";   override val name = "SbRity"   }
open class SbVier          : StreamSB() { override val mainUrl = "https://sbvier.com";   override val name = "SbVier"   }
open class SbSpeed         : StreamSB() { override val mainUrl = "https://sbspeed.com";  override val name = "SbSpeed"  }

// ── StreamTape no-suffix variants ─────────────────────────────────────────────
open class Streamtape      : StreamTape()
open class StreamtapeNet   : StreamTape() { override val mainUrl = "https://streamtape.net"; override val name = "StreamtapeNet" }
open class StreamtapeXyz   : StreamTape() { override val mainUrl = "https://streamtape.xyz"; override val name = "StreamtapeXyz" }

// ── VidSrc no-suffix / domain variants ───────────────────────────────────────
open class VidSrcXyz       : VidSrcExtractor() { override val mainUrl = "https://vidsrc.xyz"; override val name = "VidSrcXyz" }
open class VidSrcPm        : VidSrcExtractor() { override val mainUrl = "https://vidsrc.pm";  override val name = "VidSrcPm"  }
open class VidSrcIn        : VidSrcExtractor() { override val mainUrl = "https://vidsrc.in";  override val name = "VidSrcIn"  }
open class VidSrcMe        : VidSrcExtractor() { override val mainUrl = "https://vidsrc.me";  override val name = "VidSrcMe"  }
open class VidSrcMeExtractor : VidSrcExtractor() { override val mainUrl = "https://vidsrc.me"; override val name = "VidSrcMe" }
open class VidSrcNet       : VidSrcExtractor() { override val mainUrl = "https://vidsrc.net"; override val name = "VidSrcNet" }
open class VidSrcCc        : VidSrcExtractor() { override val mainUrl = "https://vidsrc.cc";  override val name = "VidSrcCc"  }

// ── Voe domain variants ───────────────────────────────────────────────────────
open class VoeLa           : Voe() { override val mainUrl = "https://voe.la"  }
open class VoeWtf          : Voe() { override val mainUrl = "https://voe.wtf" }
open class VoeSx           : Voe() { override val mainUrl = "https://voe.sx"  }
open class VoeBar          : Voe() { override val mainUrl = "https://voe.bar" }
open class VoePub          : Voe() { override val mainUrl = "https://voe.pub" }
open class VoeRun          : Voe() { override val mainUrl = "https://voe.run" }

// ── Kwik variants ─────────────────────────────────────────────────────────────
open class KwikE           : Kwik()
open class KwikF           : Kwik()
open class KwikCom         : Kwik() { override val mainUrl = "https://kwik.cx"; override val name = "KwikCx" }

// ── Superembed / 2embed no-suffix variants ────────────────────────────────────
open class Multiembed      : Superembed() { override val mainUrl = "https://multiembed.mov"; override val name = "MultiEmbed" }
open class TwoEmbed        : Superembed() { override val mainUrl = "https://2embed.cc";      override val name = "2Embed"     }
open class Twoembed        : Superembed() { override val mainUrl = "https://2embed.cc";      override val name = "2Embed"     }

// ── Okru variants ─────────────────────────────────────────────────────────────
open class Ok              : Okru() { override val mainUrl = "https://ok.ru"; override val name = "Ok" }

// ── SendVid variants ──────────────────────────────────────────────────────────
open class SendvidCom      : SendVid() { override val mainUrl = "https://sendvid.com"; override val name = "SendvidCom" }

// ── M3u8 / GeneralM3u8 variants ───────────────────────────────────────────────
open class M3u8            : GeneralM3u8Extractor()

// ── Uqload / UpStream no-suffix ───────────────────────────────────────────────
open class UpStream        : Uqload() { override val mainUrl = "https://upstream.to"; override val name = "UpStream" }
open class UpRot           : Uqload() { override val mainUrl = "https://uprot.net";   override val name = "UpRot"    }
open class UqloadCom       : Uqload() { override val mainUrl = "https://uqload.com";  override val name = "UqloadCom"}
open class UqloadIo        : Uqload() { override val mainUrl = "https://uqload.io";   override val name = "UqloadIo" }

// ── Vtube / Vtbe no-suffix ────────────────────────────────────────────────────
open class VtbeTo          : Vtube() { override val mainUrl = "https://vtbe.to"; override val name = "VtbeTo" }
open class VtCdnpro        : Vtube() { override val mainUrl = "https://vt.cdnpro.cc"; override val name = "VtCdnpro" }

// ── Mp4Upload no-suffix ───────────────────────────────────────────────────────
open class Mp4upload       : Mp4Upload()

// ── VCloud no-suffix ──────────────────────────────────────────────────────────
open class Vcloud          : VCloud()

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
        LuluStream(),
        Lulustream1(),
        Lulustream2(),
        Luluvdoo(),
        Fastdrive(),
        Streamhub(),
        Hstream(),
        Rumble(),
        GoodStream(),
        Pixeldrain(),
        PixelDrain(),
        PixelDrainDev(),
        MultiQuality(),
        Hdtube(),
        // Batch 2 — new base classes
        Emturbovid(),
        EmturbovidCom(),
        WatchSB(),
        Rabbitstream(),
        Megacloud(),
        MegaCloud(),
        Dokicloud(),
        VidStack(),
        Server1uns(),
        Maxstream(),
        Filesim(),
        Multimoviesshg(),
        Guccihide(),
        Ahvsh(),
        Moviesm4u(),
        StreamhideTo(),
        StreamhideCom(),
        Alions(),
        VidHidePro(),
        VidHidePro1(),
        VidHidePro2(),
        VidHidePro3(),
        VidHidePro4(),
        VidHidePro5(),
        VidHidePro6(),
        VidHidePro7(),
        VidHidePro8(),
        VidHidePro9(),
        VidHidePro10(),
        VidHideHub(),
        Ryderjet(),
        Gdriveplayerio(),
        Gdriveplayerapi(),
        Gdriveplayerapp(),
        Gdriveplayerfun(),
        DatabaseGdrive(),
        DatabaseGdrive2(),
        GDriveplayer(),
        SibNet(),
        Userload(),
        Supervideo(),
        Streamup(),
        Streamix(),
        Vidara(),
        Hxfile(),
        Neonime7n(),
        Neonime8n(),
        KotakAnimeid(),
        Yufiles(),
        Aico(),
        Vicloud(),
        GamoVideo(),
        GamoVod(),
        Vidsonic(),
        Vidoza(),
        Videzz(),
        Vido(),
        Mvidoo(),
        StreamoUpload(),
        Streamplay(),
        StreamSilk(),
        Meownime(),
        VidNest(),
        HubCloud(),
        Linkbox(),
        Gofile(),
        Krakenfiles(),
        Mediafire(),
        Dailymotion(),
        Geodailymotion(),
        Embedgram(),
        Filegram(),
        Evoload(),
        Evoload1(),
        ByseSX(),
        Bysezejataos(),
        ByseBuho(),
        ByseVepoin(),
        ByseQekaho(),
        Up4Stream(),
        Up4FunTop(),
        Acefile(),
        GUpload(),
        VinovoSi(),
        VinovoTo(),
        Zplayer(),
        ZplayerV2(),
        Cda(),
        GDMirrorbot(),
        Techinmind(),
        Fastream(),
        VkExtractor(),
        GoodstreamExtractor(),
        Jeniusplay(),
        Wibufile(),
        StreamEmbed(),
        ContentX(),
        StreamVid(),
        EmbedRise(),
        Guard(),
        VidPlay(),
        VidPlayOnline(),
        Sendcm(),
        Dbgo(),
        VidSrcTo(),
        Zoro(),
        Videovard(),
        Shiro(),
        SmashyStream(),
        DoodCxExtractor(),
        DoodBzExtractor(),
        DoodNlExtractor(),
        DoodWsExtractor(),
        Ds2VideoExtractor(),
        SBfull(),
        Vixcloud(),
        Gcloud(),
        VidBom(),
        Hydrax(),
        PlayerVdo(),
        FlixZaa(),
        Jihocloud(),
        Upstream(),
        MoVideo(),
        StreamlareCom(),
    ))
}
