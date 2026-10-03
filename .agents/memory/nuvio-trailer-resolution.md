---
name: Nuvio trailer resolution
description: Source provenance and playback approach for official NuvioTV trailer previews.
---

Official NuvioTV obtains trailer video IDs from TMDB movie and TV `/videos` endpoints. Playback is resolved from YouTube in-app; when adaptive streams split video and audio, the player merges both, with HLS and muxed MP4 fallbacks. Nuvio also has a remote trailer resolver fallback, which is separate from the trailer-link source.

**Why:** StreamCloud already uses TMDB for trailer IDs, so duplicating the source integration is unnecessary. Separate audio/video formats must only be returned to callers that actually merge both sources.

**How to apply:** For trailer improvements, keep TMDB as the link source and adapt YouTube playback resolution. Do not depend on Nuvio's remote fallback service unless its availability and intended use are confirmed.