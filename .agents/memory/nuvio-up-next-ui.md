---
name: Nuvio up-next UI
description: StreamCloud's Nuvio-inspired TV next-episode presentation and timing.
---

For TV playback, show a compact, light “Up next” chip during the final 60 to 10 seconds, with a skip-next icon and episode label. Do not start the autoplay countdown before the current episode ends; show the completion prompt then and honor the Autoplay next setting.

**Why:** The user asked to match Nuvio's Android TV treatment, and an early countdown can start the next episode before the current one finishes.

**How to apply:** Keep the pre-end chip informational and manually actionable, then use an end-of-playback prompt for the countdown and next-episode action.