<div align="center">

<a href="https://discord.gg/z62jDev7t3">
  <img src="https://invidget.switchblade.xyz/1240015357700214805" alt="Discord Server">
</a>

<br>

<h1>🎵 StreamCloud 🎬</h1>

<p>
<b>Android Music • Movies • TV • Plugins</b>
</p>

<p>
Native Android music & media streaming app built with Kotlin and Jetpack Compose.
</p>

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
- 📡 Cast (Chromecast) support
- 🔊 Sonos speaker support
- 🧲 Torrent streaming via TorrServer

### 🔌 Plugins

- Plugin system (CloudStream / Nuvio / Stremio)

### 🚗 Android

- Android Auto / Automotive OS support with full browse tree

### 🤖 Extras

- AI assistant screen

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