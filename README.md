# StreamCloud

Native Android music & media streaming app built with Kotlin and Jetpack Compose.

## Features

- YouTube Music streaming with multi-client Innertube waterfall resolution
- Android Auto / Automotive OS support with full browse tree
- Offline downloads with ExoPlayer cache
- Lyrics display
- Equalizer / audio effects
- Sonos speaker support
- Cast (Chromecast) support
- Torrent streaming via TorrServer
- Plugin system (CloudStream / Nuvio / Stremio)
- Movie & TV playback
- AI assistant screen

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Player**: Media3 / ExoPlayer
- **Networking**: OkHttp
- **Local DB**: Room
- **Architecture**: ServiceLocator singletons, coroutines + Flow

## Stream Resolution

Audio streams are resolved via a multi-client Innertube waterfall:

1. `ANDROID_MUSIC` — YT Music client, best audio metadata
2. `ANDROID` — standard YouTube Android client
3. `ANDROID_TESTSUITE` — YouTube-whitelisted internal test client
4. `ANDROID_VR` (x2) — Oculus Quest clients
5. `IOS` — iPhone client
6. `IPADOS` — iPad client
7. `ANDROID_CREATOR` — YouTube Studio Android client

All clients return plain stream URLs. No cipher deobfuscation required.

## Build

```
./gradlew assembleDebug
```

Requires Android Studio Hedgehog or later, minSdk 26, targetSdk 35.
