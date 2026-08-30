---
name: Android Auto queue resumption
description: Durable playback-state rules for reconnecting after the media service or app process is recreated.
---

Android Auto playback resumption must restore a bounded queue of logical media identities, the active index, and playback position after process death. An in-memory Media3 timeline is only an optimization while the service survives.

**Why:** Android Auto commonly reconnects after the app process has been recreated. Persisting resolved YouTube CDN URLs is unsafe because they expire and may be bound to request identity.

**How to apply:** Persist stable media IDs and presentation metadata only when persistent queue is enabled. Re-resolve remote streams through the normal playback safeguards, clear stale state when the queue is emptied or persistence is disabled, and fall back to recent items only when no valid snapshot exists.