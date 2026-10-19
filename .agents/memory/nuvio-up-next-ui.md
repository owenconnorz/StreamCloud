---
name: Nuvio up-next UI
description: StreamCloud's Nuvio-inspired TV next-episode presentation and timing.
---

For episodic playback, show the compact “Up next” chip before the final 20 seconds, then show the full next-episode prompt with its thumbnail and a countdown based on remaining playback time. Autoplay may advance only after the current episode actually ends; if duration is unavailable and no pre-end prompt appeared, retain the 10-second completion-prompt fallback. Honor the Autoplay next setting and let “Not now” cancel automatic playback.

**Why:** The user asked for the timer and next episode thumbnail to appear in the prompt about 20 seconds before the end, while still preventing an early transition that cuts off the current episode.

**How to apply:** Derive the visible countdown from the player position during the final 20 seconds, pause it naturally when playback is paused, and perform the automatic handoff only on the player's ended state.