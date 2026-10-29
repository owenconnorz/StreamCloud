---
name: TV display mode safety
description: Safe output-mode selection for older Android and Fire TV devices.
---

Do not change a TV's display mode while the app is starting. For automatic playback matching, use Android-reported supported modes at the currently active resolution, cap selection at 60 Hz, and skip mode changes on devices Android flags as low-RAM. Do not force resolution upgrades from the app.

**Why:** The user reports that an older first-generation Amazon TV box can show a black screen during app startup, while a newer box works. Device labels are ambiguous, and older TV firmware may not handle an app-requested display-mode change reliably.

**How to apply:** Prefer runtime-reported display modes and Android's low-RAM signal over model-name allowlists. Keep startup on the OS-selected mode, restrict player changes to same-resolution modes, and validate behavior on the affected physical device when available.
