---
name: YouTube Music video surfaces
description: Rules for deciding when Now Playing should open a synced music-video visual surface.
---

Preserve an explicit music-video selection from search through the Media3 item, accept both muxed and adaptive MP4 visual streams for the muted Now Playing surface, and give it priority over Spotify Canvas.

**Why:** Modern YouTube player responses often expose the visual track only in adaptive formats, and Innertube clients can still report audio-only or cipher-only data for a valid clip. Treating only muxed formats as video hides valid clips, while dropping the explicit selection flag makes a chosen video indistinguishable from audio playback. Canvas and the music-video surface are mutually exclusive, so rendering Canvas first hides a correctly resolved video. CDN video requests can also require the same client user agent that resolved the stream.

**How to apply:** Resolve visual streams through compatible Innertube client fallbacks, then fall back to the maintained PipePipe/BravePipe extractors with range validation. Retain the successful client user agent for the visual player and automatically open the surface only when the current item carries explicit music-video metadata; ordinary songs may also expose a video stream. Give ordinary tracks a manual Watch Video control beside the Canvas lock control, and return to Canvas when it is closed. Keep a separate generic track ID for Canvas and casting. Suppress Canvas lookup and rendering for an explicit or detected music video; keep ordinary audio playback eligible for Canvas only when no visual stream is detected.