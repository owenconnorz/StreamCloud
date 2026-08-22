---
name: Music prefetch priority
description: Priority, cancellation, and cache-ownership rules for pre-resolving YouTube Music streams.
---

Foreground playback and recovery must preempt speculative prefetch work; active queue warm-up must retain capacity even when many visible music lists are loading. URL resolution alone is not playback buffering: immediate queue entries also need a bounded byte-range warm-up in the same player cache that foreground playback reads.

**Why:** A slow or cancellation-insensitive speculative resolver can otherwise delay a user tap, starve upcoming queue items, or overwrite a recovered signed URL with the rejected client’s stale result. A cached signed URL still leaves the next song waiting for its first audio download.

**How to apply:** Keep visible-list and active-queue prefetch bounded separately, promote user-initiated resolution instead of awaiting speculative work, follow the player’s real shuffle/repeat navigation for queue look-ahead, and publish signed URLs only when the resolving generation still owns the cache entry. Byte prefetch must write through a cancellable cache-layer source using the foreground watch-URL cache key; keep it single-concurrency and skip fully downloaded tracks.