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

### 📺 Movies & TV

- 🎬 Movie & TV playback
- 📡 Chromecast support
- 🔊 Sonos speaker support
- 🧲 Torrent streaming via TorrServer

### 🔌 Plugins

- CloudStream support
- Nuvio support
- Stremio support

### 🚗 Android

- Android Auto support
- Android Automotive OS support with full browse tree

### 🤖 Extras

- AI Assistant screen

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

Requires Android Studio Hedgehog or later, minSdk 26, targetSdk 35.

---

💬 Community

Join our Discord community for support, feature requests and updates.

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://img.shields.io/discord/1240015357700214805?style=for-the-badge&logo=discord&logoColor=white&label=JOIN%20OUR%20DISCORD&color=5865F2" alt="Discord">
</a>
```