---
name: Android Auto queue resumption
description: Durable playback-state rules for reconnecting after the media service or app process is recreated.
---

Android Auto playback resumption must restore a bounded queue of logical media identities, the active index, playback position, repeat mode, and shuffle mode after process death. Keep the active item in the saved window, favor upcoming tracks over old history, and reject invalid active indices rather than resuming a different song. An in-memory Media3 timeline is only an optimization while the service survives.

**Why:** Android Auto commonly reconnects after the app process has been recreated. Truncating only the queue tail can discard the active item, while coercing its index can start the wrong track. Resolved YouTube CDN URLs are unsafe because they expire and may be bound to request identity.

**How to apply:** Persist stable media IDs and presentation metadata only when persistent queue is enabled. Reuse only local file/content/resource URIs; re-resolve remote streams through the normal playback safeguards. Clear stale state when the queue is emptied or persistence is disabled, and fall back to recent items only when no valid snapshot exists.