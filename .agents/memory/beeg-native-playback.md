---
name: Beeg native playback
description: Keep native Beeg playback tied to provider-issued media URLs and verification behavior.
---

Beeg playback may use StreamCloud's native player when the official Beeg endpoint returns a supported stream URL. Validate the provider response and keep age or browser verification failures visible; do not invent alternate URLs or bypass those checks.

**Why:** StreamCloud can offer native playback without taking control away from Beeg's access checks or accepting untrusted stream locations.

**How to apply:** When changing Beeg playback, use the provider-issued stream response, restrict accepted URLs to Beeg's secure media host, and retain the explicit retry/error path for verification or unavailable streams.
