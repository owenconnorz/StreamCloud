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

## 💬 Community

Join our Discord community for support, feature requests and updates.

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?style=for-the-badge&logo=discord&logoColor=white&label=JOIN%20OUR%20DISCORD&color=5865F2" alt="Discord">
</a>
