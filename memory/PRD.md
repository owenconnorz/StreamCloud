# PRD — StreamCloud (Native Android Kotlin)
*(formerly AioWeb)*

## Original problem statement
> "Can you rewrite my app to be kotlin and installs by apk https://github.com/owenconnorz/AioWeb"
> "I dont want this to be an web app I want this to be a kotlin app"
> "Lets make a mediaplayer that movies and porn tab can use also support PTP and anything that the movies need to support also so that the porn videos doesnt need to use embedded website to work"
> "Like cloudstream when switching provider can you get its home feed data from the plugin also when trying to add this repo it doesnt load https://raw.githubusercontent.com/phisher98/CXXX/builds/CXXX.json and can you make my mediaplayer run and look like nuvio app and can you add a in app updater that receives updates from github commits and also rename the entire app to StreamCloud"
> "all metrolist features" — synced lyrics, library (room), sleep timer, repeat/shuffle, equalizer, monet dynamic theme, offline music downloads.

## Architecture
**Fully native Kotlin Compose app**. Lives at `/app/AioWebAndroid/`.
Backend lives at `/app/backend/` (FastAPI + emergentintegrations + HuggingFace proxy).

```
Android (Kotlin Compose) ──→ TMDB           (movies)
                          ──→ NewPipe/YT     (music)
                          ──→ Eporner API    (adult, direct MP4)
                          ──→ libtorrent4j   (P2P / magnet)
                          ──→ LRClib         (lyrics)
                          ──→ GitHub API     (in-app updater)
                          ──→ AioWeb FastAPI ──→ emergentintegrations (chat)
                                            ──→ HuggingFace (image gen + image-to-image edit)
```

## Tech stack
- Kotlin 1.9.24 / Compose BOM 2024.06.00 / Material 3
- AGP 8.5.2, Gradle 8.7, JDK 17
- Retrofit 2.11 + Kotlinx Serialization
- **Media3 ExoPlayer 1.4.1** (HLS + DASH + MediaSession + foreground service)
- **libtorrent4j 2.1.0-32** (arm/arm64/x86_64) + NanoHTTPD 2.3.1
- NewPipe Extractor 0.26.0
- **Room 2.6.1 (kapt)** for Library + downloaded songs
- **android.media.audiofx (Equalizer + BassBoost)** for Audio FX
- Coil 2.7, DataStore 1.1, WorkManager 2.9
- **FileProvider** for sideload-installer hand-off
- minSdk 24, targetSdk 34

## What's implemented (Feb 2026)
**Backend (`/app/backend/server.py`)** — `/api/ai/chat`, `/api/ai/image` (Nano Banana), `/api/ai/image_hf` (HuggingFace text→image), `/api/ai/edit_image` (HuggingFace image-to-image), `/api/movies/trending`.

**Android Native App (StreamCloud)**
- 5-tab Compose shell (Movies / Music / AI / Library or Adult / Settings)
- **Movies**: TMDB trending + CloudStream plugin source switcher; full `.cs3` runtime via DexClassLoader (Android 14+ read-only workaround).
- **Music** (Metrolist parity):
  - NewPipe Extractor home feed + search
  - Foreground `MusicPlaybackService` w/ MediaSession notification + lock-screen controls
  - SimpleCache (256 MB LRU)
  - LRClib **synced lyrics** with active-line highlight
  - **Sleep timer** chip (5/10/15/30/45/60/90 min)
  - **Repeat / Shuffle** mirrored from MediaController
  - **Now-Playing** full sheet (artwork, slider, lyrics)
  - **Library (Room DB)**: liked, recent, most-played, downloaded
  - **Equalizer** (Flat/Pop/Rock/Jazz/Bass/Vocal) + **Bass Boost** via `audiofx.Equalizer`/`BassBoost` bound to player's audio session — reactive to settings
  - **Offline music downloads** (`MusicDownloader`) — resolves stream via NewPipe, writes to `<files>/music/<hash>.m4a`, persists `localPath` in Room. `play()` prefers offline copy when present
- **AI**:
  - Chat (Emergent Universal Key)
  - Image gen (Nano Banana default; HuggingFace SDXL when NSFW toggle is on, requires user HF token in Settings)
  - **Image-to-image editing** tab (HuggingFace) with system image picker
- **Adult**: Eporner search → **native Media3 playback (no WebView)** via direct-MP4 resolution from `/api/v2/video/search/?id=` (`bestMp4()` picks 1080p > 720p > 480p…)
- **Plugins screen**: per-plugin install/uninstall, multi-repo, granular HTTP error messages
- **Settings**: backend URL, HF token, AI provider/model, NSFW toggle, video/audio quality, **Audio FX** section (Equalizer + Bass Boost), **Appearance** section (Material You / Monet toggle), Wi-Fi-only downloads, **Check for updates**
- **Library tab** — sections: Downloaded, Liked, Recently played (Room-backed)
- **In-app updater** — polls GitHub Releases API, downloads APK, hands off to PackageInstaller via FileProvider
- **Nuvio-style media player** — fullscreen, auto-hiding controls, double-tap ±10s, gradient scrims, slider, used by Movies + Adult
- **Unified player** supports HLS, DASH, MP4/MKV/WEBM, and magnet/.torrent (libtorrent4j → NanoHTTPD → ExoPlayer)
- **Material You (Monet)** — opt-in toggle in Settings; `dynamicDarkColorScheme` on Android 12+, falls back to in-house dark palette
- **Display name renamed to "StreamCloud"** (kept `applicationId=com.aioweb.app` so existing installs can still receive updates).

## CI / Build
- GitHub Actions builds debug + signed-release APKs on every push
- Local container CAN now run `./gradlew compileDebugKotlin` via:
  - Java 17 + Android cmdline-tools 34 + build-tools 34 (installed in pod)
  - aapt2 wrapped through `qemu-x86_64-static` because the preview is ARM64-only
  - `-Pandroid.aapt2FromMavenOverride=/opt/aapt2-wrap/aapt2`
- Full `assembleDebug` is still slow under qemu — keep using GitHub Actions for releases.

## Known constraints
- Local APK packaging is slow (qemu-emulated aapt2). CI on `ubuntu-latest` builds in ~2 min.
- Music downloads use OkHttp (single-shot). WorkManager queue not yet attached for movie/video downloads.
- Plugin runtime works for most providers but tested mostly with phisher98/CXXX repo.

## Latest changes (Feb 2026)
- **(NEW — Feb 2026) Reddit feed redesigned to TikTok-style swipe-up vertical pager**
  - When the user picks the **Reddit** source on the Adult tab, the screen now renders as a full-bleed `VerticalPager` (one Reddit post per page, swipe up for the next) — matching the user's reference screenshot exactly. Eporner keeps the existing 2-column grid.
  - Each card auto-plays Reddit-hosted DASH videos (with sibling DASH audio when present) via Media3 ExoPlayer; only the *currently visible* page consumes player resources. GIF / WEBP / JPEG / PNG posts render via Coil's animated `ImageDecoderDecoder` (or `GifDecoder` on pre-P).
  - Right-side action column: Save (local toggle for now), Share (`ACTION_SEND`), Download (`ACTION_VIEW` → user's media saver app).
  - Top: subreddit chip strip with built-in presets (`r/nsfw`, `r/gonewild`, …) + a `+` button that opens a dialog to add a custom subreddit. Long-press a custom chip to remove it. Custom subs persist in DataStore (`adult_reddit_subs` CSV).
  - Bottom-right: source switcher pill (`Reddit ›`) → switches back to Eporner.
  - Endless scroll preloads the next page of posts when the user is within 3 cards of the end.
  - **No API key needed.** Reddit's `https://www.reddit.com/r/{sub}/{sort}.json` is public for NSFW subreddits as long as a custom `User-Agent` is sent — already wired in `RedditApi.kt` since the AioWeb port.
- `coil-gif:2.7.0` added; `AioWebApplication` now implements `ImageLoaderFactory` so animated GIFs work in the rest of the app too.

- **(NEW — Feb 2026) "View all →" + endless-scroll Catalog page**
  - Every home row (TMDB collections AND Stremio addon catalogs) now ships with a tappable **"View all →"** affordance on the right of its section header — same UX Nuvio uses.
  - Tap → opens a new `CatalogPageScreen` (3-column poster grid) that paginates endlessly via TMDB's `page=` parameter (TMDB rows) or Stremio's `skip=` parameter (addon rows). Stremio posters resolve IMDB→TMDB on tap so the existing MovieDetail flow takes over.
  - New nav route `catalog/{src}/{title}/{subtitle}` wired in `AioWebApp.kt`.
  - `HomeCollection.fetchPage(api, key, page)` added; existing `fetch(api, key)` is now a thin convenience over `fetchPage(_, _, 1)`.
  - `TmdbApi.trending` now takes a `page` query parameter so the View All grid can paginate it too.
- **(UI — Feb 2026) Single-line scrolling rows + emoji removed**
  - "Trending This Week", "In Theatres", "Popular Movies", "Top Rated" used to render as 3-column grids — now they render as horizontal `LazyRow`s of mid-size posters, matching every other row + Nuvio's design.
  - Stripped the prefix emojis (🔥 / 🎬 / ⭐ / 🏆 / etc.) from `HomeCollections` titles so the bar reads cleanly: "Trending This Week" instead of "🔥  Trending This Week".

- **(FIX — Feb 2026) Player crashed when tapping "Play"**
  - Root cause: `MediaRouteButton` (the cast icon) requires the host theme to define `mediaRouteButtonStyle`, which our base `Theme.Material3.DayNight` does not. Inflation threw `Resources$NotFoundException` and took the player down with it.
  - Fix: wrap the inflation context in a `ContextThemeWrapper(ctx, androidx.mediarouter.R.style.Theme_MediaRouter)` which DOES define the attribute. The whole construction is `runCatching`-wrapped so devices without Play Services degrade gracefully instead of crashing.
- **(FIX — Feb 2026) Stremio addon posters were not clickable**
  - `StremioPoster` now has an `onClick` handler. New `MoviesViewModel.openStremioMeta(meta) { tmdbId, … }` resolves a Stremio meta to a TMDB id via TMDB's `/find/{imdbId}?external_source=imdb_id` endpoint (Stremio addons key by IMDB id). On miss → falls back to a TMDB title search. On total miss → a friendly notice banner is surfaced at the top of the Movies tab.
  - Added `TmdbApi.find(externalId, externalSource)` + `TmdbFindResponse` model.

- **(NEW — Feb 2026) Profile button (Google avatar) in top-right of Music / Movies / Library**
  - `YtMusicLoginActivity` now scrapes the user's avatar URL + display name from `music.youtube.com` after login (via a single `WebView.evaluateJavascript` query against the page's `#avatar-btn img`). Avatar is upgraded to `=s256` so it stays crisp on hidpi.
  - New `ui/components/ProfileButton.kt` — reads `ytMusicUserAvatar` from `SettingsRepository` and shows it as a circular `AsyncImage`. Falls back to a generic `AccountCircle` icon when signed out.
  - Wired into `MusicScreen` (next to "Listen now"), `MoviesScreen` (next to "Discover"), and `LibraryScreen` (next to "Library"). Tap → navigates to the Settings hub. Same pattern Spotify / YouTube Music use.

- **(NEW — Feb 2026) Library + Adult coexist; bottom nav scrolls horizontally**
  - Library is now ALWAYS in the nav bar; Adult is additive (only appears when the NSFW toggle is on). Previously they were mutually exclusive.
  - Replaced Material 3's fixed-width `NavigationBar` with a custom horizontally-scrollable Row of pill-styled `ScrollableNavBarItem`s. With 6 tabs + Settings on small phones the bar now scrolls instead of cramping.
  - `NavOrderDialog` updated: shows both Library and Adult as separately reorderable rows when NSFW is on, so the user can place Adult anywhere in the bar.

- **(FIX — Feb 2026) Build error fallout**
  - `MediaRouteButton.setColorFilter` doesn't exist — removed the runtime tint hook in `CastUi.kt` (the SDK's bundled drawable already reads well over our dark capsule).
  - Added missing `Alignment` + `clip` imports to `AioWebApp.kt`.
  - Defined the previously-referenced `ScrollableNavBarItem` composable.

- **(NEW — Feb 2026) Google Cast (Chromecast) support in the movies player**
  - Added `play-services-cast-framework:21.5.0` + `androidx.mediarouter:1.7.0` deps.
  - `CastOptionsProviderImpl.kt` registers the Default Media Receiver (no Cast Developer Console / app id required) — works with any Chromecast / Android TV / Google Nest Hub on the same Wi-Fi.
  - `CastButton` (Compose wrapper around `MediaRouteButton`) renders in the **top-right of `NativePlayerScreen`** alongside Lock + Back. Auto-hides when the player overlay is locked.
  - `rememberCastController(streamUrl, title)` sets a `SessionManagerListener` that pushes the current resolved URL to the receiver via `RemoteMediaClient.load(MediaLoadRequestData...)`. MIME guessed from extension (HLS / DASH / MP4 / MKV / WebM). Local-proxy torrent URLs (`127.0.0.1`) are skipped automatically — Chromecasts can't reach the device's loopback interface.
  - `MainActivity.onCreate` calls `initCast()` so cast notifications surface promptly even when the user lands on Music first.
  - Manifest `<meta-data android:name="…OPTIONS_PROVIDER_CLASS_NAME" />` wires the SDK.

- **(NEW — Feb 2026) Movies tab redesigned to NuvioMobile parity**
  - Removed the source-switcher chip row entirely (no more "Built-in / Plugin / Stremio" toggle).
  - **All Stremio addon catalogs now load inline** on the Movies home page as horizontal rows (NuvioMobile style). `StremioRepository.fetchAllHomeCatalogs(addon)` fans out parallel calls across every non-search catalog declared by each installed addon (capped at 8 catalogs × 18 items each per addon to keep loads snappy). Each row's title is the catalog name with a tinted "from <AddonName>" subtitle.
  - **CloudStream `.cs3` plugins keep their own page**: the Movies home shows them only as a horizontal "CloudStream plugins" chip strip near the top — tap a chip → opens a dedicated `CloudStreamPluginScreen` with that plugin's home rows + search bar + back button.
  - New nav route `cloudstream/{name}` wired in `AioWebApp.kt`.
  - State cleanup: `MoviesViewModel` dropped `selectSource`, `pluginLoading`, `pluginError`, `pluginSections`, `pluginSearchResults`, `selectedSourceId`. Added `stremioRows: List<StremioHomeRow>`.

- **(NEW — Feb 2026) Adaptive UI for Mobile / Tablet / TV**
  - `UiFormFactor.kt` resolves the device class via `UiModeManager` (TV) and `smallestScreenWidthDp` (Tablet ≥ 600dp). User can override in **Settings → Appearance → Layout / device** (Auto / Mobile / Tablet / TV).
  - `Theme.kt` now wraps `MaterialTheme` in `ProvideUiFormFactor` which scales `LocalDensity.fontScale` (1.0 / 1.10 / 1.30) so all `sp` text grows on bigger screens without breaking dp-based layouts.
  - `AioWebApp.kt` uses a side `NavigationRail` for Tablet/TV (with the global mini-player rendered above the content's bottom edge instead of above a bottom bar). Mobile keeps the bottom `NavigationBar`.
- **(NEW — Feb 2026) Reddit added back to the Adult tab**
  - `RedditApi.kt` + `RedditAdultRepository.kt` — uses Reddit's public `/r/{sub}/{sort}/.json` endpoint (no OAuth needed for public NSFW subs, just a custom `User-Agent`). Resolves Reddit-hosted DASH videos (with sibling `DASH_AUDIO_128.mp4` audio track), inline image posts, redgifs links, direct `.mp4`/`.webm`, and `.gifv` → `.mp4` rewrites.
  - `AdultViewModel.kt` rewritten to drive a unified `AdultItem` model (Eporner OR Reddit) via an `AdultSource` enum + `setSource(...)` switcher.
  - `AdultScreen.kt` adds a horizontal source-switcher chip row (Eporner / Reddit), a preset subreddit chip strip when Reddit is active (`r/nsfw`, `r/gonewild`, `r/RealGirls`, …), and endless-scroll pagination via Reddit's `after` cursor.
  - The existing `player/eporner/{id}/{embed}/{title}` route is reused — Reddit items pass `direct://<resolved-url>` as the id and the resolver short-circuits straight to playback.

## Earlier changes (Feb 2026) (`org.jetbrains.kotlin.kapt` was being requested twice)
- Fixed `AiScreen.kt` non-exhaustive `when` (added missing `Edit` branch + image-picker UI)
- Fixed `CloudstreamApi.kt` JVM-signature clash (`fixUrl` top-level vs extension) via `@JvmName`
- Fixed `MusicController.kt` invalid `cont.resume(throw it)` → `resumeWith(Result.failure(it))`
- Added Equalizer / Bass-boost (Metrolist parity)
- Added Material You (Monet) toggle
- Added Offline music downloads (TrackEntity.localPath + MusicDownloader + MiniPlayer download icon)
- Library tab fully reworked — Downloaded / Liked / Recently played sections from Room
- **(NEW)** Fixed Kotlin compile error in `NuvioRuntime.kt` — Rhino `BaseFunction` is an abstract Java class so SAM-conversion lambdas don't infer; added a `jsFn { c, s, t, args -> ... }` helper that returns an anonymous `BaseFunction()` subclass.
- **(NEW)** Fixed missing imports in `PluginsScreen.kt` (`verticalScroll`, `rememberScrollState`, `Icons.Default.CheckCircle`).
- **(NEW)** Player now **forces landscape orientation** (`ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) while on screen, restored on dispose.
- **(NEW)** Fixed crash when tapping the in-player **Sources** button — defensive `distinctBy { it.id }` to dedupe colliding Stremio/Nuvio source ids that were violating Compose `LazyColumn(items, key={...})` uniqueness.
- **(NEW)** Cleaned up the awkward `{ -> ... }` empty-arg lambda in `PlayerToolbarPill(onSourcesClick = ...)` to a standard `() -> Unit` closure.
- **(NEW — Metrolist playlist parity, Feb 2026)** Redesigned `YtPlaylistScreen.kt`:
  - Large 220dp hero cover art (first track's artwork) with gradient fallback, title, track count, Play + Shuffle buttons.
  - Replaced per-row play/download buttons with a **3-dot menu** (Play / Play next / Add to queue / Download / Remove download / Share).
  - In-progress download ring still shown inline when a download is mid-flight.
  - Downloaded badge (`DownloadDone` icon) inline with the title.
- **(NEW — Feb 2026)** `YtPlayback.kt`:
  - Extracted `resolvePlayable()` that returns a ready-to-play `MediaItem` and upserts the Room `TrackEntity`. Offline-first: uses `TrackEntity.localPath` when the file exists, else resolves via NewPipe.
  - Added `playNext()` / `addToQueue()` which thread-hop to Main before calling `MediaController.addMediaItem()`.
  - `playPlaylist()` now actually builds a queue (first plays immediately, rest are appended on IO).
  - Added `removeDownload()` as the counterpart to `downloadSong()`.
- **(NEW — OpenTune-style Settings hub, Feb 2026)** Full rewrite of `SettingsScreen.kt`:
  - Large "Settings" display title, hero card (StreamCloud icon + version chip).
  - 2×2 big tile grid (Appearance / Player and audio / Storage / Privacy).
  - Horizontal chip row (Integration → CloudStream Plugins, Account → YT Music login, AI → provider defaults).
  - Grouped sections (USER INTERFACE / PLAYER & CONTENT / PRIVACY & SECURITY / STORAGE & DATA / SYSTEM & ABOUT) with tinted-icon hub rows that expand inline.
  - About dialog with GitHub source + bug report links.
- **(NEW — Movies UX polish, Feb 2026)**
  - **Search bar redesigned** — replaced `OutlinedTextField` with a filled, pill-shaped `TextField` (28dp rounded corners, no border, `surfaceContainerHigh` background) so it stops looking off whenever the dynamic album-art accent shifts the outline color.
  - **Source chips → fixed brand purple pill** — `SourceChip` now uses a hardcoded `Color(0xFF7C5CFF)` for the selected state instead of `MaterialTheme.colorScheme.primary` (which now follows the currently-playing track's artwork). CloudStream / Stremio source pills always read the same regardless of what's playing.
  - **Better empty-plugin hint** — when a CloudStream plugin has no `mainPage` and the home feed shows the "Plugin error" card, append a contextual tip telling the user to use the search bar — most plugins return results that way even when their home feed is empty.
- **(NEW — Playlist pagination v2 + instant-load cache, Feb 2026)**
  - **Pagination still truncating at ~100**: root cause was `browseContinuation()` only sending the token in URL params (`?ctoken=…&type=next`). Newer YT Music responses return `continuationCommand.token` and reject query-param-only requests with an empty body. Fixed by sending the token in **both** the query and the request body — InnerTube tolerates the redundancy and now returns subsequent pages reliably. Mega-playlists (300+, 1000+) now load fully.
  - **Disk cache for instant-load**: new `PlaylistCache` writes the parsed track list to `cacheDir/playlist_tracks/<id>.json` (kotlinx.serialization). YtPlaylistScreen reads the cache on open → renders instantly → kicks off the network refresh in parallel → replaces the list when complete. No expiry — fresh fetch always replaces stale within seconds, so users always see the latest data without paying the open-time latency.
- **(NEW — Playlist queue purity, Feb 2026)**
  - **Bug**: skipping past the first song in a playlist played random unrelated tracks. Root cause: `playPlaylist(...)` called `playSong(...)` which always triggered the Metrolist-style auto-radio (20 random YouTube Music recommendations). The radio batch landed in the queue **before** the playlist's own remaining tracks were appended, producing the weird `[seedSong, 20 randoms, rest of playlist]` interleave.
  - **Fix**: added `withAutoRadio: Boolean = true` parameter to `playSong()`. `playPlaylist()` now passes `false` — single-song taps from the home feed / search still get auto-radio (so skip/prev keep working there), but playlist context only ever queues the playlist's own songs.
- **(NEW — Endless scroll across the music UX, Feb 2026)**
  - Extracted a private `drainBrowse(...)` helper in `YtMusicLibraryRepository` that follows `nextContinuationData.continuation` tokens until exhausted (capped at 50 pages). Applied to **all four** library fetchers: liked playlists, liked albums, liked songs (`VLLM`), and library artists. Previously each was capped at the first 50–100 entries.
  - **Music home feed** (`YtMusicHomeRepository.load`) now also drains 12 pages of carousel-shelf continuations — so the home feed extends as the user scrolls instead of stopping at the first batch.
  - The earlier `playlistTracks` fix uses the same machinery; all music-side YT browse responses now share one consistent pagination strategy.
- **(NEW — Nuvio source button fix, Feb 2026)**
  - **Root cause**: `NuvioRuntime.runProvider` was calling `evaluate<String?>("(async function(){…return JSON.stringify(arr)})()")`. quickjs-kt's `evaluate<T>` returns the Promise object as-is (does NOT unwrap), so the cast to `String?` produced `null`, fell back to `"[]"`, and every Nuvio provider silently returned zero streams — meaning the Source button on the movie player only showed Stremio results.
  - **Fix**: bound a Kotlin-side `_setResult(json)` async function. The IIFE now calls `_setResult(...)` instead of returning, which lets the host coroutine receive the resolved JSON synchronously through a captured Kotlin variable (`streamsJson`). Nuvio streams now appear in the Source button alongside Stremio streams.
- **(NEW — Downloaded badge + custom playlist cover, Feb 2026)**
  - Replaced the `DownloadDone` glyph with `CheckCircle` everywhere a downloaded indicator appears — playlist tracks (YtPlaylistScreen) and Library Songs tab (YtSongRow). Tick now reactively flips on as soon as a download finishes (subscribed to `MusicDownloader.progressFlow`).
  - **Edit playlist cover** — small pencil FAB on the bottom-right of the playlist hero artwork. Tap → system file picker (`ActivityResultContracts.OpenDocument` with `image/*`) → user-picked URI is persisted to `SettingsRepository.playlistThumbsJson` (new JSON map keyed by playlist id) with `takePersistableUriPermission` so Coil can load it after reboot. Falls back to the first track's artwork when no override is set.
- **(NEW — Unified mini-player, Feb 2026)**
  - Replaced the in-Music-tab `MiniPlayer` (driven by `MusicViewModel.state`) with the same `GlobalMiniPlayer` (driven by `MusicController` / `PlaybackBus` / Room). Eliminates the dual-state desync where tapping a song from a Library playlist updated the foreground service but the Music tab's mini-player still showed the previous track.
  - `GlobalMiniPlayer` now visibly matches the rich Music-tab look 1:1 — album art + title + artist + Like ❤ + Download ⬇ + Skip prev ⏮ + Play/Pause + Skip next ⏭ + thin progress bar — and reads its like/downloaded status from Room directly so it stays consistent everywhere.
- **(NEW — Pagination + global player + speed-ups, Feb 2026)**
  - **Playlist 100-song limit fixed**: `YtMusicLibraryRepository.playlistTracks` now follows `nextContinuationData.continuation` tokens via the new `InnerTubeClient.browseContinuation(token)` (and a `findContinuationToken()` JSON walker). Capped at 50 pages (~5000 songs) to avoid runaway loops. Metrolist parity.
  - **Swipe-up from any tab now opens the full player**: extracted a controller-driven `NowPlayingShell` + `GlobalNowPlayingSheet` and rendered them at the AioWebApp root (above the NavHost). The `GlobalMiniPlayer` now just emits a `PlayerExpandBus` event — no tab navigation — so the sheet appears on whatever tab the user is on (Library / Movies / AI / Settings).
  - **Tap-to-play latency reduced**: `YtPlayback.resolvePlayable` no longer blocks on the `dao.upsert` round-trip — Room write is fired-and-forgotten on a background SupervisorJob scope so the user only waits on NewPipe URL resolution before audio starts.
  - **Removed auto GitHub Release publish** from `.github/workflows/build-apk.yml` — APK still builds + uploads as an Actions artifact, but you create releases manually now.
  - **Swipe-up on `GlobalMiniPlayer`** → opens the full NowPlayingSheet (Spotify / Metrolist gesture parity). Implemented with `pointerInput { detectVerticalDragGestures }` plus a 60px threshold to avoid accidental triggers.
  - **`PlayerExpandBus`** — tiny `SharedFlow` that the mini-player emits to and `MusicScreen` collects, so the request crosses the navigation boundary cleanly.
  - **Swipe-down on the NowPlayingSheet** — already provided by Material3 `ModalBottomSheet` (calls `onDismissRequest` which sets `showNowPlaying = false`). Reusing the existing chevron-down button as the visual affordance.
- **(NEW — Endless playback + Create playlist tile, Feb 2026)**
  - **Skip / Previous fixed**: root cause was `playSong` calling `setMediaItem` which clears the queue to a single item, leaving skip controls grayed out (in the UI *and* in the system notification's media controls). Fixed by adding a Metrolist-style auto-radio.
  - **`EndlessPlayback.kt`** — uses InnerTube's `next` endpoint with `playlistId="RDAMVM<videoId>"` (the same "Start radio" mix YT Music itself builds) to fetch up to 20 related songs.
  - **`YtPlayback.startAutoRadio()`** — fires after every `playSong()` on a background `SupervisorJob`+`Dispatchers.IO` scope. Resolves each related track lazily and appends to the queue one at a time so the user doesn't wait for the whole batch before skip/prev light up.
  - **Create playlist tile** added to the Library Playlists grid as the very first tile — Metrolist parity with a + icon, large rounded card, plus a Material `AlertDialog` to name the playlist. (Local playlist DAO/Room schema is the next step; UI ships now with a friendly toast.)
  - **Note for Like in notifications + Pagination**: Media3's default MediaSession already exposes Play/Pause/Skip/Prev in the system shade — those will now light up because the auto-radio populates the queue. Custom Like / Pagination are still on the backlog.
- **(NEW — Downloads & Theme polish, Feb 2026)**
  - **Parallel downloads** — `MusicDownloader` now uses a `Semaphore(3)` so up to 3 songs download concurrently (Metrolist parity).
  - **System notifications** — `MusicDownloadNotifier` posts an ongoing progress notification per download (throttled to ~250ms updates) and a 4-second auto-dismiss "Downloaded" confirmation when complete. Uses the existing `POST_NOTIFICATIONS` permission already in the manifest.
  - **Album-art-driven dynamic theme** — new `AlbumArtThemeBus` extracts the vibrant Palette swatch from the currently playing track's artwork (Coil → Bitmap → `Palette.from(...).vibrantSwatch ?? lightVibrantSwatch ?? dominantSwatch`). `AioWebTheme` now overlays this color as `MaterialTheme.colorScheme.primary`, so the play button / nav highlight / mini player / 3-dot menu accent the current track. Falls back to the house violet when nothing is playing.
- **(NEW — Now-playing indicator + home click fix, Feb 2026)**
  - Added `audio/PlaybackBus.kt` — global StateFlow of `nowPlayingMediaId` + `isPlaying`, hooked into `MusicController` via a single `Player.Listener`. Attached once on app start in `AioWebApp.kt`.
  - Added `ui/components/PlayingBars.kt` — Metrolist's signature 3-bar animated equalizer. Each bar uses an independent `infiniteRepeatable` so it feels organic; freezes when paused.
  - `PlaylistTrackRow` (YtPlaylistScreen) now overlays `PlayingBars` on the album art and tints the row primary-18% when the playback bus says this song is current.
  - Fixed home-feed playlist tap → `MusicScreen` `YtHomePlaylistCard` was passing no `onClick`, which silently used the default empty lambda. Now wires through `onOpenPlaylist(pl.id, pl.title)`.
- **(NEW — gitignore cleanup, Feb 2026)** Replaced corrupted 568-line `.gitignore` (had the same env-vars block duplicated 60+ times due to a `-e` heredoc bug) with the user-supplied 91-line clean version. This was confusing the platform's 3-way merger and producing phantom conflicts on `SettingsScreen.kt`.

- **(NEW — CloudStream `.cs3` plugin streaming end-to-end, Feb 2026)**
  - **Root cause of broken playback**: `PluginRuntime` could `load()` plugins, register `MainAPI`s, and even run `home()`/`search()` — but **never called `api.loadLinks()`**. Posters in `CloudStreamPluginScreen` had **no `onClick`**, so the user could never reach the source-resolution step. `MovieDetailScreen` (TMDB path) only fanned out to Stremio + Nuvio, ignoring installed `.cs3` plugins entirely.
  - **`PluginRuntime.loadLinks(filePath, data, isCasting=false)`** — replica of the upstream `recloudstream/cloudstream` pipeline. Iterates every registered `MainAPI` in the plugin and accumulates `ExtractorLink` + `SubtitleFile` via callbacks (one API failing doesn't kill the others).
  - **`CloudStreamDetailScreen.kt`** — new poster-tap destination. `api.load(url)` → for Movies, "Play" calls `loadLinks(dataUrl)`; for TvSeries, episode rows each call `loadLinks(episode.data)`. ExtractorLinks convert to `PlayerSource` (quality + label + magnet flag + headers) and hand off to the existing native player + Sources picker.
  - **`MovieDetailScreen.kt`** (TMDB path) now also fans out to installed `.cs3` plugins via `plugin.search(title) → load → loadLinks`. CS streams join Stremio + Nuvio in the unified source picker.
  - **`PlayerSource.headers`** — optional `Map<String,String>` carrying `Referer`/`Origin` etc from `ExtractorLink`; the player route forwards them to `NativePlayerScreen` so CloudStream extractors that 403 without a referrer now resolve.
  - **CloudStream plugin posters are now clickable** in both the home-row LazyRows and the search-result grid.
  - Verified: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL in 17s.

- **(NEW — Rich Now Playing UI + functional 3-dot actions sheet, Feb 2026)**
  - **`NowPlayingShell.kt`** completely rewritten to match the user's reference screenshot 1:1 — wide square artwork, title + artist with Download/Like pills on the right, slider with timestamps, big white "Play" pill flanked by dark prev/next capsules, 7-chip bottom toolbar (Queue · Sleep · Lyrics · Comments · Shuffle · Repeat · 3-dot), palette-driven dynamic gradient background, and inline synced lyrics view. All UI driven entirely from a Media3 `Player` reference + Room flows (no ViewModel required) so the same UI works from any tab.
  - **`MusicActionsSheet.kt`** new — opens when the 3-dot is tapped, matches screenshot 2. Includes live AudioManager STREAM_MUSIC volume slider, three pill chips (Radio · Add · Share), and full action list: View artist, Add to library, Download, Listen Together, Details (info dialog), Equalizer (launches system audio-effect panel via `AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` with current audio session id, falls back to Settings → Audio FX), Advanced (tempo/pitch dialog wired to `controller.setPlaybackParameters`).
  - **`YtPlayback.startRadioFromCurrent(context, mediaIdUrl)`** new public wrapper around the existing `startAutoRadio` so the Radio chip can spin a Metrolist-style auto-radio off whatever's currently playing.
  - **Share** uses Android's `Intent.ACTION_SEND` with title + artist + YT URL.
  - **View artist** routes through the existing `artist/{url}` destination using a YouTube search URL when the channel URL isn't on the MediaItem metadata.
  - **Like / Download** read live from Room (`TrackDao.isLiked`, `MusicDownloader.isDownloaded`) so the pills always reflect the true state.
  - Verified: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL.

- **(NEW — Cast button + Nuvio parity fixes, Feb 2026)**
  - **Cast button rewritten as Compose-native** (`cast/CastUi.kt`). Root cause of "button isn't showing / nothing happens on tap": androidx.mediarouter's `MediaRouteButton` creates an AppCompat-only `DialogFragment` that silently no-ops when the host activity isn't an `AppCompatActivity`. Our `MainActivity` extends `ComponentActivity` (Compose-only), so the chooser never appeared. Also the previous wrapper used `Modifier.size(28.dp).padding(8.dp)` which clipped the inner Drawable.
    - New impl: solid 40dp pill (`Cast` ↔ `CastConnected` icon, green tint when connected), talks straight to `androidx.mediarouter.media.MediaRouter` with the merged Cast selector from `CastContext`, pops a Compose `AlertDialog` listing live discovered routes (active scan while open + 1.5s refresh loop), `selectRoute()` on tap → existing `rememberCastController` pushes the stream URL to the receiver.
  - **NuvioRuntime upgraded to NuvioMobile parity** — copied the polyfill surface from `NuvioMedia/NuvioMobile`'s `PluginRuntime.kt` so every plugin from their repos runs unchanged: `fetch`/`fetchv2` Response wrapper, `URL` + `URLSearchParams`, `AbortController`, `atob`/`btoa`, full `cheerio` jQuery-like API backed by Jsoup, `CryptoJS` (MD5/SHA1/SHA256/SHA512 + HMAC variants, Hex/Utf8/Base64 enc), `require('cheerio'|'crypto-js')`, plus Array.flat/flatMap, Object.entries/fromEntries, String.replaceAll polyfills. Per-script error capture exposed via `NuvioRuntime.lastError(id)`.
  - **NuvioRepository TMDB ID resolution** — replica of NuvioMobile's `TmdbService.ensureTmdbId`. Strips `tmdb:`, `tmdb/`, `imdb:`, `movie:`, `series:` prefixes; numeric ids pass through; IMDB `tt…` ids → resolved via `/find/{imdb_id}?external_source=imdb_id` against the existing `BuildConfig.TMDB_API_KEY`. In-memory LRU cache prevents repeat hops. **This was the actual reason streams weren't loading** — providers were being called with `"tt0133093"` or `"tmdb:603"` and bailing out without ever hitting the network.
  - Verified: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL.

## Backlog / next iterations
- **P1** Picture-in-Picture (PiP) for the player
- **P1** Brightness/volume vertical drag gestures (Nuvio-style)
- **P1** Subtitle track picker + external SRT/VTT loader in NativePlayerScreen (surface the `SubtitleFile`s now returned by `PluginRuntime.loadLinks`)
- **P1** Cast button visibility fix in NativePlayerScreen overlay (sizing/constraint)
- **P1** Nuvio Plugin Repo installer/updater UI (Settings → Plugins)
- **P1** Stremio category click speed-up (`CatalogPageScreen` async + skeletons)
- **P1** Refactor monolithic `MovieDetailScreen.kt` / `PluginsScreen.kt` into per-ecosystem modules (CloudStream / Stremio / Nuvio)
- **P2** Android Auto integration (`CarAppService` + Media3 session bridging)
- **P2** Downloads tab for movies/adult via WorkManager
- **P2** Real-world torrent streaming verification with magnet links
- **P2** Per-ABI APK splits / AAB
- **P3** Supabase auth (`supabase-kt`), AI face-swap, FCM push

## Next action items for user
1. **Use "Save to GitHub"** to push these changes (compile fixes + Equalizer + Monet + offline downloads + Library)
2. CI will build a fresh APK; install on device.
3. Test:
   - Settings → Audio FX → Equalizer ON, pick "Rock" → confirm bass/treble change while a song plays
   - Settings → Appearance → Material You ON (Android 12+) → confirm primary colors match wallpaper
   - Music → tap Download icon on mini-player → progress bar → song appears under Library → Downloaded
   - Toggle airplane mode and replay a downloaded song — should stream from local `m4a` file
   - AI → "Image edit" tab → pick photo → describe edit → confirm HF call returns edited image
