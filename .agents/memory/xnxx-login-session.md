---
name: XNXX login sessions
description: Preserve XNXX's first-party login and verification flow while keeping its session local and provider-scoped.
---

Use XNXX's official sign-in page and let the site present any age or browser verification it requires. Persist the WebView session on the device so users are not repeatedly asked to sign in. Play videos in StreamCloud's native player; do not fall back to WebView playback. Keep XNXX cookies off cloud storage and out of media-CDN requests; an app-side session indicator is not proof of age verification or a valid account.

**Why:** The user wants XNXX's own verification prompt to appear when the provider requires it, wants the session retained locally, and wants video playback to remain in StreamCloud's native player.

**How to apply:** Preserve this boundary in future XNXX authentication, session, and playback changes. Match native page requests to the login WebView's user-agent where needed. Send stored cookies only to XNXX-owned page requests, never to stream/CDN URLs.
