---
name: StreamCloud TV interface reference
description: Persistent TV navigation and visual parity choices based on user-provided reference captures.
---

On TV, use a persistent black, icon-only top navigation bar instead of a side drawer or labeled capsule. Keep the profile action at the left, primary destinations centered, and search/settings at the right. Place the home hero directly under the bar; keep its title, type, year, rating, and synopsis visible without Play Now or More Info buttons. The profile chooser uses centered rectangular cards on a black background. TV search uses a remote-controlled on-screen keyboard and updates results as text is entered. Home shelves use wide 16:9 cards, and the hero artwork/details follow the focused movie or series.

**Why:** The user-provided reference screens show this layout, and the hidden drawer, circular profile picker, submit-only search, portrait shelf cards, and a hero unrelated to focused titles did not match their expected TV experience.

**How to apply:** Keep these behaviors consistent across TV routes. Profile cards must remain D-pad focusable; debounce TV search keystrokes without requiring a separate submit to show matches. Resume the automatic hero carousel when no shelf title is focused.
