## V2084 — Android TV Movies focus return

### Android TV
- **Reliable Movies hero return** — the top navigation immediately restores the Movies banner, and Down targets Play before falling back to nearby content.

## V2081 — Android TV Movies navigation fix

### Android TV
- **Movies banner focus returns reliably** — D-pad Down from the top navigation scrolls to the banner and focuses Play; D-pad Up returns focus to the navigation bar.

## V2078 — Continue Watching, Music, and TV navigation fixes

### Android TV navigation
- Focus returns to screen content after navigation, with a fallback when the destination is not ready.

### Music
- Paused-player controls are clearer, and Music navigation works more reliably with a remote.

### Continue Watching
- Deleted items stay removed after Nuvio sync; deletion markers remain queued until the remote record is confirmed gone.

## V2070 — TV detail title and artwork improvements

### Movie and TV details
- **More balanced TV title logos** — Logo artwork is sized more comfortably within the detail hero.
- **Sharper detail backdrops** — Movie and TV pages use higher-resolution TMDB artwork.

## V2067 — Android TV focused cards and detail logo fix

### Android TV Movies home
- Focused portrait cards now transition to a landscape layout and prefer backdrop artwork; the selected Poster style remains in effect when cards are unfocused.
- TMDB and Stremio rows show the available title/logo treatment on focused landscape cards.

### Movie and TV details
- The TV detail hero reserves only a modest top-navigation clearance and uses the remaining viewport to keep the title logo and Play controls visible when focused.

## V2064 — Android TV poster and detail logo fix

### Android TV Movies home
- Portrait, Landscape, and Auto card styles now remain consistent while a card is focused.
- Portrait cards keep their poster thumbnails with title/logo art instead of being forced into landscape.

### Movie and TV details
- The detail hero now fits the available TV viewport, keeping the title logo visible when Play receives focus.

## Latest — Android TV detail, artwork, and focus fixes

### Movie and TV details
- TV detail title logos fit within the hero without clipping.

### Movies home
- Android TV cards prefer landscape or backdrop artwork and show title/logo overlays; visible CloudStream cards request matching artwork before focus and display text while logos load.

### Navigation
- Pressing Back restores focus to screen content instead of the navigation bar across Android TV screens.

## Latest — TV detail title logo and backdrop seam fix

### Movie and TV details
- TV hero height now fits the available space below the top inset, keeping the title logo visible when Play receives focus.
- TV uses one continuous backdrop treatment so no hard horizontal edge crosses the title; mobile layout is unchanged.

## Latest — Full-screen trailers and larger title logos

### Movie and TV details
- The Trailer button now opens a dedicated full-screen player, with loading and unavailable states, a close control, and Android TV Back support.
- Full-screen trailers do not loop.
- TMDB title logos are larger and left-aligned on Android TV detail pages.

## Latest — TV detail banners fill the top inset

### Movie and TV details
- TV detail artwork now continues from the display top through the hero, while titles and playback controls remain below the top chrome.

## Latest — Android TV detail artwork width

### Movie and TV details
- TV detail backdrops now fill the hero area instead of appearing narrow and centered; mobile artwork and poster-only detail pages retain fit-to-area scaling.

## Latest — TV detail banner focus fix

### Movie and TV details
- TV detail artwork now stays below the top edge when the Play button receives focus; phone spacing is unchanged.

## Latest — Movie detail banner top clearance

### Movie and TV details
- Detail banners now start lower on mobile and Android TV to keep the artwork clear of the top edge.

## Latest — Home, artwork, and Continue Watching fixes

### Continue Watching
- Deleted items disappear immediately and stay hidden while sync completes; Nuvio tombstones remain queued until deletion is confirmed.

### Movies home
- TMDB home rows load from a persisted cache before refresh, with bounded collection and title-logo requests.
- Banner tint is sampled from the active artwork and drawn over the image; TMDB title logos appear on hero and poster surfaces.
- TV Play and More Info actions use pill-shaped buttons.

### Details and TV navigation
- Detail artwork preserves the full banner, scales to mobile width, and carries its extracted color around the image and down both sides before fading to black.
- Android TV remote focus handoffs now honor request results and fall back or retry when a target is not ready.

## Latest — Nuvio progress sync and movie-detail trailers

### Nuvio sync
- **Watch progress stays accurate across devices** — imported Nuvio progress preserves its original remote IDs, converts seconds and epoch-second timestamps to milliseconds, repairs older imports, and does not overwrite newer local playback.
- **Progress deletion syncs against the right Nuvio record** — account- and profile-scoped tombstones retain the original remote keys, serialize with other syncs, and remain queued until a linked profile confirms the delete. The app reports when the profile needs linking or the account needs reconnecting.

### Movie and TV details
- **Trailer playback is optional on detail pages** — TMDB title logos appear over the artwork hero, with a toggle to switch between the trailer and static artwork.
- **Trailer resolution uses available visual streams** — muxed or suitable adaptive YouTube video is selected, while the artwork remains available when no preview can be resolved.

## Latest — Movie home cards, profiles, and Continue Watching

    ### Movie home
    - **Expanded cards keep static artwork and titles** — TMDB, Stremio, and CloudStream home cards retain their artwork and title/logo treatment instead of playing trailer previews. Detail-page previews and the home hero preview are unchanged.

    ### Profiles and launch
    - **42 Nuvio catalog avatars** — The profile chooser adds the Nuvio avatar collection while keeping older saved avatar selections compatible.
    - **Choose a profile at launch** — The profile chooser opens before the home screen, and the splash-screen logo is removed.

    ### Continue Watching
    - **Watched episodes stay out of Continue Watching** — Episodes already marked watched no longer reappear as in-progress cards.

# StreamCloud

## Previous — Player navigation and TV up next

### Movie and TV playback
- **Back returns to details after playback** — Returning from movie or show playback no longer reopens the source picker or starts playback again.
- **Nuvio-style TV up next** — A compact chip appears during the final minute for TMDB-backed episodes. When playback ends, a dialog offers the next episode and a 10-second countdown when Autoplay next is enabled.

## Previous — Android TV home and streaming improvements

    ### Android TV home
    - **Focused catalog cards expand reliably** — moving focus to TMDB and Stremio cards opens their details.
    - **Artwork-matched hero gradient** — the top of the home banner uses the dominant color extracted from its artwork.

    ### Movie and TV playback
    - **Faster provider-priority auto-play** — playback starts when the first eligible provider in the configured priority order returns a stream, without waiting for all providers.

    ## Previous — Continue Watching and MoviePlayer improvements

### TV playback
- **Continue Watching follows the latest in-progress episode** — home cards stay aligned with the episode page, even when synced progress is under 1%.

### MoviePlayer
- **Clearer progress-bar focus** — the bar briefly scales up instead of showing the purple border.
- **Hold Left or Right to seek repeatedly** — rewind or skip forward while controls are hidden or the progress bar is focused.

## Previous — Fix a music playback thread crash

### Music playback
- **Liked-song refresh no longer crashes playback** — Media3 player state is read on the main thread, and results are ignored if playback has moved to another track.

## Previous — Continue Watching follows the current episode

### TV playback
- **Continue Watching shows the latest in-progress episode for each series** — Home cards use the same progress threshold as episode details, so a short synced position no longer leaves an older episode on the card.

## Previous — Android Auto music discovery and playback recovery

### Android Auto
- **Personalised Home shortcuts** — Speed Dial and Quick Picks surface tracks from recent, liked, and most-played music.
- **Better voice search** — Android Auto searches local tracks and YouTube Music, plays the best match, and distinguishes no results from an unavailable provider.
- **More reliable playback resumption** — saved queues preserve the active track, position, repeat, and shuffle settings; remote streams are resolved again after a service restart.
- **Current browse shelves** — relevant Android Auto sections refresh when the music library changes, and liked tracks are de-duplicated.

## Previous — Prioritise stream providers and auto-play

### Movie and TV playback
- **Choose which providers try first** — installed Stremio add-ons, Nuvio providers, and CloudStream plugins can be reordered in Settings.
- **Skip the source picker when desired** — auto-play starts with the first provider that returns a stream, with remaining provider results kept as playback fallbacks.
- **Auto-play works from direct source pages too** — while preferred providers are checked, StreamCloud shows progress instead of the source picker; downloads still require an explicit source.

## Previous — Continue series playback from the right episode

### TV playback
- **Resume or play the next episode** — the main button continues an episode from its saved position or starts the next unwatched episode, and follows the selected season.

## Previous — Title logos on mobile home thumbnails

### Movie and series cards
- **Titles appear over the artwork** — mobile home thumbnails show TMDB title-logo art when available, with readable text fallback when no logo exists. Android TV card behavior is unchanged.

## Previous — Faster, more reliable Movies home loading

### Movies home
- **Cached sections stay visible during refresh** — the home screen keeps showing existing movie rows while updated results load.
- **Categories appear as they finish loading** — each section can update without waiting for every TMDB request to complete.
- **Refreshes avoid duplicate work** — settings changes are combined, in-flight requests are deduplicated, and stale results refresh when you return after 15 minutes.

## Previous — Continue Watching crash and TV error text fix

### Continue Watching
- **Scrolling no longer crashes on shows with multiple saved episodes** — Each progress card uses the show's, season's, and episode's identity, so separate episode entries keep distinct keys.

### Android TV diagnostics
- **Crash details are easier to read on TV** — The previous-crash dialog title and report text use white for better contrast; mobile styling is unchanged.

## Previous — Android TV poster and loading quick fix

### Android TV
- **Focused movie and series cards expand again** — TMDB and Stremio catalog posters now open their focused details on Android TV.
- **Trailer previews return on focused cards** — TMDB-backed previews start for focused posters when a playable trailer is available.

### Home loading
- **Fewer title lookups at startup** — Landscape card titles render locally instead of requesting remote logos for every composed card; Stremio TMDB matching waits until a card remains focused.

## Previous — Watched episodes stay in sync across devices

### Playback sync
- **Reliable episode completion** — TV completion updates queue a guaranteed Nuvio sync, so frequent playback-position updates cannot replace the watched-state change.
- **Mobile catches up on return** — Nuvio refreshes when the mobile app returns to the foreground, so TV-watched episodes appear on mobile without switching profiles.

## Previous — Add Nuvio-style trailer audio

### Trailer previews
- **Adaptive YouTube trailers can play sound** — StreamCloud now pairs adaptive video and audio tracks when YouTube does not provide a muxed stream, with HLS, muxed MP4, and silent video as fallbacks.

## Previous — Keep Android TV trailers loading

### Trailer previews
- **Trailer previews stay available when YouTube has no audio-backed format** — The resolver now searches all supported clients for muxed audio/video first, then uses video-only adaptive playback only as a fallback.

## Previous — Android TV trailer audio fix

### Trailer previews
- **Trailer audio is restored on Android TV** — Preview resolution now requires an audio-backed stream instead of choosing a silent video-only adaptive track.

## Previous — Android TV playback, trailers, and episode progress

### Playback & navigation
- **More reliable Android TV source navigation** — Reopening Sources for a saved item returns to its movie or episode, while Back and long-press actions follow the remote's intent.
- **Trailer previews support adaptive video** — Previews can use video-only streams when needed and remain silent when no audio track is available.

### Watched episodes & resume
- **Episode-level watch history** — Completed episodes are tracked individually by show, season, and episode rather than marking the entire series watched.
- **Nuvio episode watch state syncs correctly** — Episode identifiers and metadata remain attached to the correct show during account synchronization.
- **Episode resumes preserve their identity** — Explicit progress keys are kept; episode-specific fallback keys are generated only when needed.

## Previous — Android TV hero and trailer autoplay

### Android TV
- **Navigation-safe, artwork-matched hero** — The rounded home banner now sits below the top navigation and uses a gradient tinted with its sampled artwork.
- **Trailer autoplay** — TMDB-backed home slides start audio-backed previews after two seconds; movie-detail previews start immediately.
- **Trailer recovery** — Empty TMDB video results trigger a fresh lookup; stream-resolution and playback failures retry once.

## Previous — Android TV and CloudStream improvements

### Android TV
- **More reliable trailer previews** — TV previews refresh a failed stream URL once to recover from transient playback errors.
- **Rounded home hero** — The Android TV banner is inset and uses rounded corners.
- **Horizontal profile picker** — TV profiles appear in a single row; the mobile profile grid is unchanged.

### CloudStream / CS3
- **Cleaner repository settings** — Removed the unused plugin search field while keeping repository and plugin management controls.
## Previous — Full-bleed Android TV hero and trailer audio

### Android TV
- **Full-bleed home banner** — movie artwork now reaches the screen edges, with carousel markers inside the banner and a top fade for navigation contrast.

### Trailer previews
- **Audio-backed previews** — trailer resolution skips video-only streams and uses muxed audio/video fallback streams, avoiding silent playback.

## Previous — Android TV movie detail and trailer hotfix

### Android TV
- **Inset widescreen home hero** — the movie billboard uses a rounded, inset layout.
- **Focused poster highlight** — the focus border stays on the expanded image instead of framing the title below it.
- **Side-by-side detail hero** — TV movie details place the title and playback actions beside the artwork and trailer.

### Trailer previews
- **Trailer audio is enabled** — movie-detail previews now play audible, looping trailers.

## Previous — Episode-first series details and trailer autoplay

### Series details
- **Episodes come first on mobile and Android TV** — the shared TMDB/Nuvio page now places season and episode browsing before secondary series information.
- **CloudStream and Stremio series pages** — episode lists appear before secondary metadata; selecting a Stremio episode requests that episode’s streams, while addons without episode lists retain their series-level stream fallback.
- **TV episode provider check** — CloudStream plugins are recognized when starting an episode from shared TMDB details.

### Trailer previews
- **TMDB movie and series banners** — available YouTube trailers autoplay muted and looped, with artwork retained if no playable trailer is available.
- **Home-card previews** — the existing focus delay remains unchanged.

## Previous — Stremio collection controls

### Stremio
- **No automatic collection imports** — adding an addon or opening Plugins no longer creates collections automatically; use an addon's Refresh action to import its catalogs on demand.
- **Local collection cleanup** — the upgrade removes collections marked as Stremio-generated while preserving manual and Nuvio-owned collections.

## Previous — Major Nuvio, music, and playback update

### Nuvio
- **Profile-scoped account sync** — Nuvio accounts, installed providers, saved repositories, and imported media now stay isolated per StreamCloud profile.
- **Automatic Nuvio synchronization** — profile changes and account updates refresh the correct Nuvio data without touching unrelated CloudStream plugin repositories.
- **More reliable Nuvio media imports** — imported movies and shows keep their metadata, artwork, provider identity, and Continue Watching context.
- **Provider request compatibility** — TMDB `/series` requests are corrected to `/tv`, and the common `api_kev` query typo is repaired before provider fetches.
- **Updated Nuvio login flow** — account setup follows the current Nuvio server and keeps profile navigation scoped correctly.

### Music
- **Personalized DJ radio** — DJ sessions can use listener preferences to build more relevant radio journeys while keeping preference signals on-device.
- **Smoother DJ transitions** — crossfade, transition announcements, narration timing, and voice tuning work together without pausing the music.
- **YouTube Music account menu** — account details and profile artwork are available from the music experience.
- **Dynamic music theming** — music surfaces follow the active appearance palette more consistently.

### Playback and navigation
- **Pornhub playback recovery** — playback can fail over between available qualities instead of stopping at the first rejected stream.
- **Live TV and Local Files tabs** — both content areas are available from the main navigation alongside the existing movie and music sections.
- **Expanded navigation** — the five-item navigation layout and profile-aware navigation graph keep section switching consistent.

### Music
- **Album songs stay audio-first** — ordinary songs now open with album artwork and audio controls instead of loading a visual music-video player.
- **Automatic music-video surfaces** — explicitly classified music videos use the visual player automatically, without a manual video/music switch.
- **Wide Listen together cards** — station cards now use a wide 16:9 banner layout on both playlist and song-based home rails.
- **More reliable home-feed playback metadata** — the YouTube Music models and home parser now stay aligned with the player’s song-versus-video classification.

### Release packaging
- Mobile and Android TV release APKs are published as separate assets.

## Android TV mini-player and Library layout

### Android TV
- **Library header makes room for playback** — removed the duplicate Library heading and StreamCloud branding on the TV Library page so the mini-player fits without crowding the centered search and tabs.
- **Compact mini-player metadata** — long track titles and artist names now scroll automatically, while the distracting playback bars have been removed.

## V1821 — Android TV search button hotfix

### Android TV
- **Round search control** — the top navigation search button now matches the circular Music action buttons below it while staying in the same position.
- **Consistent focus styling** — the search control uses the same filled surface and circular remote-focus border as the surrounding TV actions.

## V1817 — Android TV music focus and search hotfix

### Android TV
- **Music D-pad navigation works again** — pressing Down from the TV navigation now lands on the Music actions and continues into the content rails.
- **Music Search opens ready to use** — the search field is visible and focused immediately on TV, without an extra search-icon press.
- **Dynamic search styling** — the TV Music Search screen now follows the active app theme accent and uses matching focus treatment.
- **Cleaner search header** — removed the redundant TV back button while retaining remote Back navigation.

## V1813 — Android TV music navigation hotfix

### Android TV
- **Music navigation is clearer** — removed the duplicate Music search action and route the single top-navigation search button to Music Search or Movie Search based on the active section.
- **Mini-player stays docked** — starting music no longer opens Now Playing automatically; the top-left mini-player remains available for remote access to the full player.
- **Compact Speed dial** — music shortcuts now use a horizontal TV-friendly rail instead of oversized 3×3 cards.
- **Remote focus is visible** — playback controls, progress seeking, toggles, and action chips now use stronger focus borders and filled pills.

## V1809 — Android TV music player redesign

### Music
- **Spotify-style TV Now Playing** — artwork-led backdrop, prominent track metadata, wide progress controls, and clearer playback actions make music easier to use from a TV remote.
- **Quick return to playback** — a compact, focusable mini-player appears in the top-left of TV home screens whenever music is active and opens the full player without stopping playback.
- **Artist access from the TV player** — the new “About the artist” section provides a direct route to the artist search page.
- **Casting controls retained** — play/pause, seeking, skip, disconnect, shuffle, repeat, and like actions continue to use the existing playback and receiver state.

## V1792 — Android TV movie player and navigation improvements

### Movie player
- **Remaining time counts down correctly** — the right-hand playback timestamp now shows the time left in the film instead of repeating the full duration.
- **Cleaner TV controls** — the Portrait/Landscape control is hidden from the Android TV movie player because TV playback is already landscape-focused.
- **Source picker keeps remote focus** — opening the source picker moves the remote focus into the source panel and keeps it there until Back is pressed.
- **Player focus restores cleanly** — closing the source picker returns focus to the movie-player controls.

### Navigation
- **TV movie search navigation** — remote Down from the search field moves to the first real recent search or result.
- **Separated Music actions** — the Music screen’s Search, DJ, History, and Trending buttons now have clear spacing.

## V1781 — Android TV search and settings improvements

### Android TV
- **Theme-colored search focus** — the search field and movie or series result cards now use the selected Movies theme for their visible focus border and pill treatment.
- **Keyboard and remote Back behave consistently** — submitting with the TV keyboard Search action or pressing remote Back while the search field is focused closes the keyboard, refreshes the query, and moves focus to the first result.
- **Cleaner TV navigation** — the redundant top-left back button is hidden on Android TV while remaining available on mobile and tablet layouts.
- **TV-focused settings** — mobile-only settings such as Android Auto, Backup and restore, App logs, Picture in Picture, gesture controls, Discord Rich Presence, and external-browser options are hidden from the TV settings interface.

## V1772 — Android TV colored focus pills

### Android TV
- **Nuvio-style controller focus** — focused navigation items, settings rows, controls, and content targets now use the selected theme color with a filled pill background and animated border.
- **Theme-controlled focus color** — the Appearance → Color Theme selection now controls the TV controller focus color across the app.
- **Remote-friendly settings** — theme swatches, the settings back control, updater install button, and other previously plain controls now expose the same visible focus treatment.
- **Updater release notes** — available update notes are shown directly in the settings updater panel with a scrollable “What’s new” section.

## V1771 — Fire TV Cube installation compatibility

### Android TV
- **First-generation Fire TV Cube support** — the TV APK now includes both 64-bit ARM and 32-bit ARM native libraries.
- **Broader TV compatibility** — older Fire TV devices with a 32-bit Android userspace can install the TV APK while x86 architectures remain excluded.

## V1769 — Android TV movie search focus fix

### Android TV
- **D-pad Down now highlights results** — completed searches move focus directly to the first real movie or series card instead of an invisible list anchor.
- **Search/Enter works reliably** — after submitting a query with the Android TV keyboard, focus is restored to the first available result once it loads.
- **Nested result rows remain navigable** — the first-card handoff leaves normal left/right and row-to-row D-pad navigation intact.

## V1764 — Separate mobile and Android TV APKs

### Release packaging
- **Separate device APKs** — mobile and Android TV builds are now published as distinct release assets.
- **Smaller TV updates** — the Android TV APK targets ARM64 devices and excludes unused native architectures, reducing the download and install size substantially.
- **Safer automatic updates** — the in-app updater selects the TV APK on television devices and the mobile APK on phones and tablets.
- **Update compatibility preserved** — both variants keep the existing StreamCloud package ID and release signing configuration.

### Android TV
- **Search navigation works with a remote** — pressing D-pad Down from the search field now moves focus into the first result row.

APK assets: `StreamCloud-mobile-release.apk`, `StreamCloud-tv-release.apk`

## V1752 — Android TV and DLNA music casting controls

### Remote playback
- **Casting progress stays live** — Android TV and DLNA receivers now report their current position, duration, play state, and buffering state throughout playback.
- **Full transport controls** — the phone and Android TV interfaces can Play/Pause, seek, skip Previous or Next, and Disconnect while the receiver owns playback.
- **Track changes stay in sync** — queue transitions update the active Cast or DLNA destination without briefly routing commands back to the phone player.

### Receiver experience
- **Complete music metadata** — title, artist, album, and artwork are sent to supported Google Cast and DLNA receivers.
- **Responsive casting UI** — Now Playing, mini-player, TV, and casting handoff surfaces share the same receiver-owned state and identify the active destination.
- **More reliable sessions** — delayed receiver status no longer resets progress, and Cast suspension, reconnection, handoff, and disconnect paths clean up only the session they own.

APK: `StreamCloud-release.apk`

## V1740 — YouTube Music downloads and playback recovery

### YouTube Music
- **Downloaded songs play offline reliably** — completed downloads now resolve to their stable video-ID cache entry instead of trying to play the YouTube watch page.
- **Downloads start faster** — the extra CDN probe was removed, and shared stream resolution begins while the playback service starts.
- **Player and download caches work together** — already-buffered ranges can be reused when creating a permanent download.

### Recovery
- **CDN failures recover cleanly** — rejected signed URLs and incompatible partial data are evicted after `403` or `416` responses before retrying from byte zero.
- **Client fallback is safer** — rejected YouTube client labels are temporarily excluded while the maintained extractor fallback remains available.
- **Library download status is consistent** — stable video IDs and older watch-URL entries are both recognized.

### Movie settings
- Added preferred Dolby audio format settings that prioritize supported Dolby tracks without forcing unsupported decoding.

APK: `StreamCloud-release.apk`

## V1698 — Movie watchlists

### New
- Create separate named watchlists for movies and TV shows from Library.
- Save a movie or show to one or more watchlists from browsing, TMDB detail pages, and CloudStream detail pages.
- Select one item with a long press or the explicit Select action.
- Select all items, clear the selection, move selected items, or remove them from the current list.
- Delete custom watchlists without affecting movies saved in other lists.

### Reliability
- Existing saved items remain in the default Watchlist after the database migration.
- CloudStream, Reddit, and RedGifs source metadata remains attached when items are moved.
- Duplicate movies are prevented within each custom watchlist while allowing the same movie in multiple lists.
- Android TV list controls and selection states remain visible for remote navigation.

APK: `StreamCloud-release.apk`