---
name: App palette scope
description: The user's intended screens for the reference color palettes and artwork-derived colors.
---

Apply the selected reference palette to Movies home, Search, Library, and Settings. Keep Music, Adult, and movie detail pages on their existing artwork-derived or legacy theme behavior. Remove banner-art tinting from Movies home without removing artwork-based colors from detail pages.

**Why:** the user extended the black-and-white palette to Search while explicitly excluding Music and Adult; movie details should also keep their existing artwork-derived or legacy behavior.

**How to apply:** when changing shared theme or palette code, scope the new palette to Movies home, Search, Library, and Settings; verify that Music, Adult, global artwork-driven playback colors, and detail-page banner palettes remain intact.
