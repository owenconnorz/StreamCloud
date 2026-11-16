---
name: Wear OS music companion
description: Pairing, browsing, and stream-safety constraints for StreamCloud's Wear OS companion.
---

The Wear OS companion is a paired controller and browser for StreamCloud's existing music library, not a separate provider-authentication client. Keep the watch and phone apps on the same application ID and release signing identity so Wear Data Layer messaging can reach the phone app.

**Why:** Wear Data Layer routing is package-scoped, while provider sessions and library access already live on the phone.

**How to apply:** Browse the phone's MediaLibrary and route supported playback to either device without copying account credentials or session cookies to the watch.

Direct watch streaming is limited to provider tracks whose audio URLs the phone can resolve without web-session headers. Keep signed URLs in memory only, send the resolver's matching user agent, and ask the phone to evict a URL rejected by the CDN. Use phone playback for providers or tracks that need private web-session state.

**Why:** Provider authentication and stream identity belong to the phone; carrying web cookies or stale signed URLs onto a watch can break playback and expose private session state.

**How to apply:** Recheck provider support and session-header requirements before adding watch playback for another source; retain phone playback as the fallback.
