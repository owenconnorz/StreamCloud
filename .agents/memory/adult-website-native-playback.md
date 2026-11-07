---
name: Adult website native playback
description: Provider-specific native stream constraints for Porn4Days and PornHD3x.
---

Use only full media URLs explicitly published by the official video page or its own player configuration. Porn4Days currently publishes an HLS URL on `video.amateur4days.com`; keep that host and `.m3u8` validation. PornHD3x pages may expose third-party server choices and preview clips. Do not treat `data-preview` clips as full videos or extract streams from external host players. If no supported direct stream is published, use the provider page's official player inside the app WebView. Keep top-level navigation restricted to the official site and explicitly recognized HTTPS player hosts; never broaden it to arbitrary embed domains.

**Why:** The user prefers StreamCloud's native player when a provider publishes a direct stream, but external host players have their own playback and verification. Extracting their media URL can misrepresent playback or skip provider access checks.

**How to apply:** Keep provider verification visible, validate HTTPS hosts and media types, and fall back to the provider's in-app official player when no supported direct stream is available.
