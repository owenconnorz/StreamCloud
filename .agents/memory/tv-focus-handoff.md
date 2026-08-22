---
name: Fire TV focus handoff
description: Android TV spatial focus behavior around the persistent Nuvio-style navigation button
---

Fire TV spatial focus search is unreliable when a persistent overlay navigation button is the only initially focused control. Down/Right must move directly to a real, visible page control; an invisible focus bridge can become a dead end instead of a useful handoff.

**Why:** Fire Cube navigation could remain trapped on the top-left hamburger even though page controls were visible, and a hidden intermediary merely moved the trap. Touch-only clickable cards did not consistently participate in TV focus search.

**How to apply:** Keep the TV hamburger accessible, but have Down/Right invoke directional focus movement from it. Interactive TV cards and rows must be explicit focus targets with a visible focus border, so the shared traversal works across Movies, Music, Library, and other pages.