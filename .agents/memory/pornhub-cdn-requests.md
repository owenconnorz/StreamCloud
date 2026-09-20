---
name: Pornhub CDN requests
description: Provider-specific requirements for Pornhub thumbnails and signed video playback.
---

Pornhub CDN assets behave like browser-origin resources: image requests need a Pornhub referrer and browser user agent, while signed media URLs should be resolved again after a playback rejection rather than retried unchanged. Media definitions can also include a `/video/get_media` API URL labelled as MP4; exclude it from native playback sources.

**Why:** The page can render successfully while direct Coil or native-player requests are rejected, and the media URL embedded in the page can expire independently of the page URL.

**How to apply:** Keep Pornhub thumbnail requests browser-like, prefer a native-compatible progressive stream when available, filter provider API endpoints from playable sources, and make retry controls fetch a fresh media definition.