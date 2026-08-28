# StreamCloud

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