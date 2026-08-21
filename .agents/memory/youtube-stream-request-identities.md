---
name: Signed stream request identities
description: Requirements for fetching signed YouTube CDN streams after an extractor or InnerTube resolver returns a URL.
---

Request a signed YouTube stream URL with the exact user agent returned by its resolver, including when the request is made through an in-app proxy for another playback device.

**Why:** YouTube CDN URLs can be accepted by the receiving device’s local proxy yet rejected when that proxy fetches upstream using a different client identity, leaving playback at zero duration with no media.

**How to apply:** Carry the resolver user agent together with the stream URL through player, cache, retry, and proxy models. Do not replace it with a generic YouTube Music user agent; use a default only when no resolver identity exists.