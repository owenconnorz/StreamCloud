---
name: Adult website native playback
description: Provider-specific native stream constraints for Porn4Days and PornHD3x.
---

Use only full media URLs explicitly published by the official video page or its own player configuration. Porn4Days currently publishes an HLS URL on `video.amateur4days.com`; keep that host and `.m3u8` validation. PornHD3x pages may expose third-party server choices and preview clips. Do not treat `data-preview` clips as full videos or extract streams from external host players. If the official page has no supported direct stream, show a clear error and let the user open the official page.

**Why:** The user wants playback in StreamCloud's native player, but guessing a stream URL, using a teaser, or extracting from an external host can misrepresent playback and skip provider access checks.

**How to apply:** Keep provider verification visible, validate HTTPS hosts and media types, and preserve an explicit official-page fallback whenever the provider does not publish a supported direct stream.
