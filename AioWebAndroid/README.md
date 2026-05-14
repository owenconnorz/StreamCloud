# AioWeb Android (Kotlin)

A **fully native Kotlin Android app** for AioWeb — Movies, Music, AI, and Library — built with Jetpack Compose + Material 3.

> This is a brand-new native rewrite, separate from the [AioWeb web project](https://github.com/owenconnorz/AioWeb). The web app continues to live as a deployed PWA; this repo is the native Android edition that you sideload as an APK.

## Features

| Tab | What it does | Native tech |
|---|---|---|
| 🎬 **Movies** | Trending + popular browse, search, full detail screen, trailer playback, Vidsrc embed | TMDB v3 REST API · Coil image loading · Compose grid/lazy-row · ExoPlayer-ready |
| 🎵 **Music** | Search YouTube music, audio-only playback, mini-player, background-ready | NewPipe Extractor · Media3 ExoPlayer · MediaSessionService |
| 🤖 **AI** | Chat with GPT-5.1 / Claude Sonnet 4.5 / Gemini 2.5 Pro · Generate images with Nano Banana | Backend proxy (FastAPI + emergentintegrations) · Universal LLM Key |
| 📚 **Library** | Saved movies/songs/AI creations | DataStore (Room expansion planned) |
| ⚙️ **Settings** | Backend URL · default LLM provider/model | DataStore Preferences |

## Architecture

```
┌─────────────────────────────────────────┐
│   AIOWEB ANDROID (Kotlin / Compose)     │
├─────────────────────────────────────────┤
│ UI: Jetpack Compose · Material 3        │
│   ↳ MoviesScreen / MusicScreen /        │
│     AiScreen / LibraryScreen / Settings │
│ ViewModels: StateFlow + Coroutines      │
│ Net: Retrofit + Kotlinx Serialization   │
│ Audio: Media3 ExoPlayer + MediaSession  │
│ Music sources: NewPipe Extractor        │
│ Image loading: Coil                     │
│ Storage: DataStore Preferences          │
└─────────────────────────────────────────┘
            │            │            │
            ▼            ▼            ▼
     ┌────────────┐ ┌──────────┐ ┌──────────────┐
     │ TMDB API   │ │ YouTube  │ │ AioWeb       │
     │ (movies)   │ │ (NewPipe)│ │ FastAPI      │
     └────────────┘ └──────────┘ │  /api/ai/*   │
                                 └──────┬───────┘
                                        ▼
                          ┌─────────────────────────────┐
                          │ emergentintegrations        │
                          │   ↳ OpenAI / Anthropic /    │
                          │     Gemini · Nano Banana    │
                          └─────────────────────────────┘
```

This is **NOT a WebView wrapper** — every screen is native Compose. The only network dependencies are:
- **TMDB** (free public read key) for movie metadata
- **YouTube** via NewPipe Extractor (no API key, audio extraction at runtime)
- **AioWeb FastAPI backend** (deployable from `/app/backend/` of the parent web repo) for AI calls

## Tech stack

- Kotlin 1.9.24 · Android Gradle Plugin 8.5.2 · Gradle 8.7 · JDK 17
- Jetpack Compose BOM 2024.06.00 · Compose Compiler 1.5.14
- Material 3 · material-icons-extended
- Retrofit 2.11 · OkHttp 4.12 · Kotlinx Serialization 1.7
- Media3 1.4.1 (ExoPlayer + Session)
- NewPipe Extractor 0.24.2
- Coil 2.7 · DataStore 1.1 · WorkManager 2.9
- minSdk 24 · targetSdk 34 · compileSdk 34

## Build & install

The container that scaffolded this project is ARM64 Linux, so APKs are built on **GitHub Actions x86_64 runners** (free).

### Get the APK
1. Push this repo to GitHub
2. Add 4 release-signing secrets (see "Release signing" below)
3. Wait for the **Build APK** workflow to complete (~3-5 min)
4. Download from **Releases** tab → `AioWeb-release-signed-N.apk` → sideload onto Android

### Build locally (x86_64 host or Android Studio)
```bash
git clone <this-repo>
cd AioWebAndroid
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

CI builds signed-release APKs when these GitHub Secrets are configured:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `release.keystore` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias (default `aioweb`) |
| `KEY_PASSWORD` | key password |

Generate a keystore yourself with `./scripts/generate-keystore.sh` — it prints all four values for you to paste into GitHub.

Without secrets, CI falls back to producing only the debug APK + an unsigned-release APK.

## Configuration

The default backend URL is set at build time:
```kotlin
buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://aio-android-port.preview.emergentagent.com\"")
```

End users can override this at runtime in **Settings → Backend URL**.

To use your own TMDB API key, change `TMDB_API_KEY` in `app/build.gradle.kts`.

## Backend setup

The AI features (Chat + Image gen) call your FastAPI backend at:
- `POST /api/ai/chat` — text generation via emergentintegrations
- `POST /api/ai/image` — Nano Banana image generation

The backend is at `/app/backend/server.py` of the parent AioWeb repo. Deploy via Vercel/Render/Railway/Emergent and put the URL in Settings.

## Project structure

```
AioWebAndroid/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/aioweb/app/
│   │   ├── MainActivity.kt              # Compose host
│   │   ├── AioWebApplication.kt         # NewPipe init
│   │   ├── audio/
│   │   │   └── MusicPlaybackService.kt  # Media3 background service
│   │   ├── data/
│   │   │   ├── ServiceLocator.kt
│   │   │   ├── SettingsRepository.kt
│   │   │   ├── api/{TmdbApi,AioWebBackendApi}.kt
│   │   │   ├── network/Net.kt
│   │   │   └── newpipe/{Downloader,Repository}.kt
│   │   └── ui/
│   │       ├── AioWebApp.kt             # Bottom nav + NavHost
│   │       ├── theme/{Theme,Color}.kt
│   │       ├── screens/                 # 5 tabs + movie detail
│   │       └── viewmodel/               # State holders
│   └── res/                             # icons, themes, strings
├── .github/workflows/build-apk.yml      # CI: debug + signed release
├── scripts/generate-keystore.sh
├── build.gradle.kts · settings.gradle.kts
└── gradlew · gradlew.bat
```

## What's still planned

- 📥 Native download manager (WorkManager + Room) — currently stubbed Library tab
- 🔐 Supabase auth (optional — kept off the critical path)
- 🎞️ Native HLS/DASH video player UI (Media3 component already configured)
- 🎬 Movie scraper plugin port from the web project's TS plugin system
- 🎨 AI image-to-image editing (face swap)
- 📦 Per-ABI APK splits + AAB for Play Store

## License

Same as the parent AioWeb project.
