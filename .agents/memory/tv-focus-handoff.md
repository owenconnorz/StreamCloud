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