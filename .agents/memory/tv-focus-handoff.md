---
name: Fire TV focus handoff
description: Android TV spatial focus behavior around the persistent Nuvio-style navigation button
---

Fire TV spatial focus search is unreliable when a persistent overlay navigation button is the only initially focused control. Down/Right must have an explicit handoff into the active page, and the handoff may need to retry while asynchronous page content becomes focusable.

**Why:** Fire Cube navigation could remain trapped on the top-left hamburger even though page controls were visible, because spatial focus search did not reliably cross from the overlay into centered or delayed content.

**How to apply:** Keep the TV hamburger accessible, but route Down/Right into a shared content focus bridge rather than relying only on geometric focus search. Preserve the bridge at the shared app-shell level so Movies, Music, Library, and other TV pages receive the same behavior.