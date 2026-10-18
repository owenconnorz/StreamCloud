---
name: App palette scope
description: The user's intended screens for the reference color palettes and artwork-derived colors.
---

Apply the selected reference palette only to Movies home, Library, and Settings. Keep Music and movie detail pages on their existing artwork-derived or legacy theme behavior. Remove banner-art tinting from Movies home without removing artwork-based colors from detail pages.

**Why:** the user explicitly narrowed the palette change to those three screens and asked to leave Music and movie details unchanged.

**How to apply:** when changing shared theme or palette code, scope the new palette to those routes and verify that global artwork-driven playback colors and detail-page banner palettes remain intact.
