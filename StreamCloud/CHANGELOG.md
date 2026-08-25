# StreamCloud

## Android TV and Music recovery

### What's new
- **Music no longer fails silently on TV** — unavailable YouTube Music feeds now show a clear recovery message with retry and reload actions instead of leaving the screen empty.
- **Fallback music content is handled safely** — fallback sections appear only when they contain playable content, and mood-only sections no longer masquerade as a usable feed.
- **Movie browsing recovers visibly** — loading, empty, and error states now explain what happened and provide a focused retry action.
- **Cancelled loads cannot overwrite fresh content** — overlapping movie and music requests are coalesced or cancelled safely so stale results do not replace the current screen.
- **Now Playing connection failures are recoverable** — the global player sheet shows connection progress and offers a retry after a timeout or failed controller connection.

### Android TV navigation
- **Navigation focus is deterministic** — Menu, Up, and Down handoffs target the real first content control across Movies, Music, Library, and Settings.
- **Focus survives recovery** — when temporary loading or error content becomes available content, focus returns to the durable screen rather than getting lost.
- **Settings opens on the first real row** — D-pad navigation no longer lands on an invisible or non-interactive placeholder.
- **Focus requests match the supported Compose API** — TV navigation remains compatible with the app's current Compose version while preserving safe fallbacks when a target is not attached.

Compiled and packaged by GitHub Actions.