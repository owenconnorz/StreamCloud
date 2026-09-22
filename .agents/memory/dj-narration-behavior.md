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

Follow-up narration should run in a deliberate between-song gap: pause the newly selected item before
it becomes audible, speak clearly, then resume that item. Do not speak over the song when the user
has reported that narration is hard to hear.

**Why:** The DJ host needs to be intelligible, and pausing only at the transition avoids interrupting
the previous song or losing the next track's queue position.

**How to apply:** Vary host updates across two or three automatic transitions, preserve user pauses,
and resume only when the pause was initiated by the DJ announcement itself.

Voice realism must be improved through the device's installed TTS voices and prosody controls, not
through imitation of a named person or a hardcoded proprietary voice.

**Why:** Android devices expose different TTS engines and voice inventories; the app cannot guarantee
a studio-quality voice, but it can prefer the best matching installed voice and keep speech audible.

**How to apply:** Select a matching locale voice by quality and latency, tune pitch/rate per StreamCloud
preset, and use full utterance volume without changing the user's system volume.