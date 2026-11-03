---
name: Beeg native playback
description: Keep native Beeg playback tied to provider-issued media URLs and verification behavior.
---

Beeg playback may use StreamCloud's native player when the official Beeg endpoint returns a supported stream URL. Its current HLS response includes `key`, `data`, `media`, `multi`, and `/_TPL_/{id}.mp4.m3u8` path segments; an HTTP 200 response in this format is a valid stream, not a verification prompt. Validate the provider response and keep explicit age or browser verification failures visible; do not invent alternate URLs or bypass those checks.

**Why:** Beeg can change its signed path format while still returning a valid playlist. Treating an unrecognized HTTP 200 body as a verification failure gives users a misleading message; only actual provider verification responses should say verification is required.

**How to apply:** When changing Beeg playback, use the provider-issued stream response, validate its current path structure, restrict accepted URLs to Beeg's secure media host, and reserve verification messaging for explicit provider verification responses such as HTTP 403.
