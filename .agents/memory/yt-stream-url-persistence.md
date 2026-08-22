---
name: YouTube stream URL persistence
description: Safety rules for retaining resolved YouTube Music stream URLs between application restarts.
---

Persist resolved YouTube Music stream URLs only while their advertised expiry remains valid, and remove them immediately when the CDN rejects one.

**Why:** Googlevideo URLs are short-lived and can also be rejected before their expiry when YouTube changes client or PoToken requirements. Retrying a stale persisted URL adds delay and can block the resolver's healthy fallback path.

**How to apply:** Any future stream-cache, playback retry, or persistence change must share the same expiry check and rejection eviction behavior for both in-memory and persisted entries. On a rejected read, evict incomplete cache spans from both playback cache layers using the active media item's exact cache key; do not reconstruct a canonical watch URL or remove completed downloads.