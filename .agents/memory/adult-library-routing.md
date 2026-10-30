---
name: Adult library routing
description: Keep saved adult-provider entries out of the movie-detail route.
---

Saved adult watchlist entries use synthetic IDs derived from their provider and video ID; those IDs are not TMDB IDs.

**Why:** If a saved provider item falls through to movie navigation, the synthetic ID is sent to TMDB and can produce an HTTP 404 instead of opening the provider player.

**How to apply:** Dispatch saved library entries by provider media type before the movie fallback, and keep each provider's URL/ID parsing consistent with playback from its source feed.
