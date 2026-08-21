---
name: Android Auto voice playback
description: How the Media3 library service must handle Android Auto and Gemini spoken music requests.
---

Treat a voice play request as a search request, not as a media URI. Resolve its Media3 request metadata to a playable media item before returning it from the media-session callback.

**Why:** Android Auto sends a query-bearing placeholder item for spoken “play” commands. Passing that placeholder into the normal URI attachment path leaves the player without a valid stream and Google reports that it cannot connect.

**How to apply:** Keep voice-search resolution and browse-search results on the same search path, and cache a small result set in-process so Android Auto's separate search callbacks do not repeat the network lookup.