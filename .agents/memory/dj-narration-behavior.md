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

Continuous personalized sessions should append generated chapters to the active Media3 queue rather
than replacing it, and must update the DJ session's expected queue before the append reaches Media3.

**Why:** DJ chapter growth must preserve the listener's current position and avoid the queue-integrity
guard interpreting automatic recommendations as a user-selected queue replacement.

**How to apply:** Only extend while a personalized DJ session owns the queue; keep ordinary queue
changes as an explicit exit from DJ mode.

Transition narration must never pause the Media3 player. Use speech audio attributes that let Android
duck media when appropriate, and cancel only when the user or an external interruption pauses playback.

**Why:** Pausing and restarting the player around every host update creates audible gaps and can lose
the current handoff position.

**How to apply:** Let TTS complete independently over the active track, and vary host updates across
two or three automatic transitions rather than using a fixed two-track cadence.