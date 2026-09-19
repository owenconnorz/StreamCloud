# StreamCloud

## V1791 — Android TV movie player and navigation improvements

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