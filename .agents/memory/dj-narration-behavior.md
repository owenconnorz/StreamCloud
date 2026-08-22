---
name: DJ narration behavior
description: Product rule for StreamCloud DJ narration and voice customization.
---

StreamCloud DJ narration is always enabled for every DJ session, including the one-tap personalized
mix. The options sheet may customize the original Android TTS voice style, but it must not expose a
narration on/off switch.

**Why:** The DJ experience is defined by its spoken introduction and occasional original transition
updates; making speech opt-in caused the header autoplay path to skip the intended DJ presentation.

**How to apply:** Keep the intro before the first track and transition announcements after the
configured automatic-track interval. If Android TTS is unavailable, fall back to starting music
without blocking playback, but never add a user preference that disables narration.