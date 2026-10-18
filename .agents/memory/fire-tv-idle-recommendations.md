---
name: Fire TV idle recommendations
description: Fire TV and Android TV idle behavior for StreamCloud's in-app recommendation screen.
---

Show idle recommendations as an in-app screen while StreamCloud remains in the foreground. Do not describe or implement it as an overlay inside Amazon Gallery; Gallery is a system-owned screen. Android has a separate `DreamService` screensaver API, but Fire TV's support for third-party selectable dreams has not been confirmed.

**Why:** Fire TV's Gallery is launched by the OS and cannot be painted over by an ordinary app. The dependable approach is to show recommendations before the system screensaver takes over, then return to the app when the remote is used.

**How to apply:** Keep the idle screen limited to the Movies home, reset its timer on user input, rotate artwork to avoid a permanently static image, and only keep the screen awake while the in-app idle view is active.
