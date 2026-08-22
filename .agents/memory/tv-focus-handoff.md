---
name: Fire TV focus handoff
description: Android TV spatial focus behavior around the persistent Nuvio-style navigation button
---

Fire TV spatial focus search is unreliable when a persistent overlay navigation button is the only initially focused control. Down/Right must move directly to a real, visible page control; an invisible focus bridge can become a dead end instead of a useful handoff.

**Why:** Fire Cube navigation could remain trapped on the top-left hamburger even though page controls were visible, and a hidden intermediary merely moved the trap. Touch-only clickable cards did not consistently participate in TV focus search.

**How to apply:** Keep the TV hamburger accessible, but have Down/Right invoke directional focus movement from it. Interactive TV cards and rows must be explicit focus targets with a visible focus border, so the shared traversal works across Movies, Music, Library, and other pages.

Nested TV rails should rely on the focusable item's normal list reveal behavior rather than adding an unconditional `BringIntoViewRequester` to every focused card. Repeated D-pad events should also be consumed after throttled focus movement, including at a group edge.

**Why:** A second reveal request made horizontal movement in a nested rail scroll its parent vertically, while unconsumed repeat events let focus search and list scrolling run together.

**How to apply:** Keep focus animation lightweight, use `focusGroup()` for rail traversal, and let the first D-pad press use normal spatial search while throttled repeats are handled exclusively by the focus manager.

For the Nuvio-style home flow, initial TV focus belongs on the first available movie card, not the corner launcher. Pressing Left from top-level content opens the drawer directly; the drawer begins with Home and its profile header is informational rather than the default focus target.

**Why:** Starting on a persistent hamburger trapped remote users at the top of the screen and required an extra navigation step before they could browse.

**How to apply:** Keep the corner control available for touch, but non-focusable while the drawer is closed. Restore the first movie card after closing Home’s drawer and retain Menu as a hardware fallback.

When a screen needs to own initial and repeated D-pad traversal, attach the shared handler outside the `LazyColumn`/`LazyRow` content DSL and opt into initial presses explicitly; keeping it in the lazy-list argument can trigger misleading Kotlin scope resolution errors.

**Why:** The compiler treated a locally declared key-event lambda inside the list call as list-scope symbols, while the shared modifier works reliably at the screen container.

**How to apply:** Let the shared modifier move and consume Up/Down on the Movies screen, while the app shell handles only the drawer boundary after the first-card focus callback.