<div align="center">

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?style=for-the-badge&logo=discord&logoColor=white&label=JOIN%20OUR%20DISCORD&color=5865F2" alt="Discord">
</a>

# 🎵 StreamCloud 🎬

**Android Music • Movies • TV • Plugins**

Native Android music & media streaming app built with Kotlin and Jetpack Compose.

</div>

---

## ✨ Features

### 🎵 Music

- YouTube Music streaming with multi-client Innertube waterfall resolution
- 🎤 Lyrics display
- 💾 Offline downloads with ExoPlayer cache
- 🎚️ Equalizer / audio effects
- Youtube login
- Spotify login
- Spotify canvas media screen

### 📺 Movies & TV

- 🎬 Movie & TV playback
- 📡 Chromecast support
- 🔊 Sonos speaker support
- 🧲 Torrent streaming via TorrServer

### 🔌 Plugins

StreamCloud supports CloudStream 3 plugins with improved compatibility:

- ✅ **MainAPI Providers** — Content providers (movies, TV, anime, etc.)
- ✅ **ExtractorAPI** — Custom video stream extractors
- ⚠️ **Status:** Actively improved with better error reporting and multi-strategy class loading
- 📝 **Compatibility:** Compatible with CloudStream 3 plugins compiled for recent SDK versions

#### Plugin Support Details

**What Works:**
- Loading and executing CloudStream 3 plugins (.cs3 files)
- Multi-strategy class loading with fallback mechanisms
- Plugin API registration (both MainAPI and ExtractorAPI)
- Plugin settings callbacks
- Context delegation for plugin resource access

**Known Limitations:**
- Some older plugins may require updates to work with StreamCloud's classloader setup
- Plugins with undeclared dependencies may fail to load
- Plugin extractors are registered but integration with the player is in progress

**Troubleshooting Plugin Issues:**
If a plugin fails to load, check the app logs for specific error messages. Common issues:
- `NoClassDefFoundError` — Plugin references missing dependencies
- `ClassCastException` — Classloader mismatch (improved with fallback strategies)
- `NoSuchMethodError` — API incompatibility (check CloudStream SDK version)

#### For Plugin Developers

See `PLUGIN_DEVELOPMENT.md` for guidelines on:
- Building compatible plugins
- Using MainAPI and ExtractorAPI
- Testing against StreamCloud
- Debugging plugin loading issues

### 🚗 Android

- Android Auto support
- Android Automotive OS support with full browse tree

### 🤖 Extras

- coming soon

---

## 🛠️ Tech Stack

- **💜 Language:** Kotlin
- **🎨 UI:** Jetpack Compose
- **▶️ Player:** Media3 / ExoPlayer
- **🌐 Networking:** OkHttp
- **🗄️ Local DB:** Room
- **⚡ Architecture:** ServiceLocator singletons, coroutines + Flow

---

## 🎧 Stream Resolution

Audio streams are resolved via a multi-client Innertube waterfall:

1. `ANDROID_MUSIC` — YT Music client, best audio metadata
2. `ANDROID` — Standard YouTube Android client
3. `ANDROID_TESTSUITE` — YouTube-whitelisted internal test client
4. `ANDROID_VR` (x2) — Oculus Quest clients
5. `IOS` — iPhone client
6. `IPADOS` — iPad client
7. `ANDROID_CREATOR` — YouTube Studio Android client

All clients return plain stream URLs. No cipher deobfuscation required.

---

## 🚀 Build

```bash
./gradlew assembleDebug
```

Requires Android Studio Hedgehog or later, minSdk 26, targetSdk 35.

---

## 📖 Documentation

- [Plugin Development Guide](PLUGIN_DEVELOPMENT.md) — How to build plugins for StreamCloud
- [Plugin Runtime Architecture](docs/PLUGIN_RUNTIME.md) — Technical details on plugin loading

---

## 🔄 Cloudstream Compatibility Improvements

This section summarises improvements made to bring StreamCloud's CloudStream 3
compatibility closer to the reference [`recloudstream/cloudstream`](https://github.com/recloudstream/cloudstream)
implementation.

### Comparison Findings

| Area | recloudstream/cloudstream | StreamCloud (before) | Gap |
|---|---|---|---|
| `SubtitleHelper` language DB | 170 languages, 4 lookup indexes | ~25 hardcoded entries, 1 index | Plugins calling `fromTwoLettersToLanguage()` got `null` |
| `SubtitleHelper` API | `fromTwoLettersToLanguage`, `fromThreeLettersToLanguage`, `fromTagToEnglishLanguageName`, `fromTagToLanguageName` | `fromCodeToLangTagIETF`, `fromLanguageToTagIETF` only | Missing methods = broken subtitle track names |
| `getQualityFromName` | "4k" only | "4k" only | "uhd", "fhd", "hd", "sd", "qhd" → `Unknown` |
| Plugin error messages | — | Covered ~7 error patterns | ~500xx, timeout, no-results patterns missing |

### Implemented Changes

1. **`SubtitleHelper.kt` — Comprehensive language database (170 entries)**
   - Replaced the 25-entry hardcoded map with the full 170-language database from
     `recloudstream/cloudstream`, indexed on IETF tag, ISO 639-1, ISO 639-2/B, ISO 639-3,
     and OpenSubtitles codes.
   - Added `fromTwoLettersToLanguage(input)` — deprecated in the canonical library but still
     called by many existing plugins; without it subtitle track names were `null`.
   - Added `fromThreeLettersToLanguage(input)` — same compat reason.
   - Added `fromTagToEnglishLanguageName(languageCode)` — returns the English name for any
     recognized code, used by the player's subtitle track picker.
   - Added `fromTagToLanguageName(languageCode, localizedTo)` — returns a JVM-localised name
     with English fallback.
   - Improved `fromCodeToLangTagIETF` and `fromLanguageToTagIETF` to use the database rather
     than a small fixed `when` block, so all 170 languages resolve correctly.

2. **`ExtractorApi.kt` — `getQualityFromName` quality-string aliases**
   - Added aliases: `uhd`→2160p, `fhd`/`full hd`/`fullhd`→1080p, `hd`→720p, `sd`→480p,
     `qhd`/`2k`→1440p, interlaced suffixes (`1080i`, `720i`, …).
   - Improves quality-based source sorting for plugins that label streams with these strings
     instead of numeric values.

3. **`CloudStreamDetailScreen.kt` — Expanded `friendlyPluginError` patterns**
   - Added recognition for: `MalformedJsonException`, `JsonParseException`, `NoSuchMethodError`,
     `Connection refused`, `CertPathValidatorException`, HTTP 5xx status codes, timeout strings,
     and "no links found" / "0 results" diagnostics.
   - Users see concise, actionable messages instead of raw stack-trace excerpts.

### Validation

- 34 new `SubtitleHelperTest` unit tests — all pass.
- 26 new `QualityFromNameTest` unit tests — all pass.
- Existing `NuvioCompatTest`, `NuvioTmdbSanitizerTest`, and `PluginCompatibilityTest` are
  unaffected.

### Known Limitations / Follow-up Suggestions

- `SubtitleHelper.getFlagFromIso()` (flag-emoji helper) is not yet ported; requires the
  `lang2country` mapping table (~200 entries). Low priority for functionality.
- `SubtitleHelper.fromLanguageToTagIETF` does not yet perform Levenshtein fuzzy matching
  (used by cloudstream for partial names like "English Subtitle"). Can be added if plugins
  need it.
- DRM (`DrmExtractorLink`) key-rotation and licence-URL fallback logic from cloudstream has
  not been ported as StreamCloud does not yet expose a DRM licence-request flow.

---

## 💬 Community

Join our Discord community for support, feature requests and updates.

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?style=for-the-badge&logo=discord&logoColor=white&label=JOIN%20OUR%20DISCORD&color=5865F2" alt="Discord">
</a>
