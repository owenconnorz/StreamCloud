---
name: App palette scope
description: The user's intended screens for the reference color palettes and artwork-derived colors.
---

Use the Appearance palette as the single source of truth throughout Movies, including movie and TV details, stream selection, and movie catalogs. Remove the duplicate Movie Settings theme picker while keeping poster style there. Keep Music and Adult on their existing theme behavior.

**Why:** the user confirmed the Appearance palette should apply to every Movies screen, including detail pages and the stream picker, instead of maintaining a separate Movies palette.

**How to apply:** read `color_palette` for all Movies surfaces and remove `movies_theme` consumers. Keep music and adult routes out of Movies-only palette wrappers, and retain artwork as imagery rather than using it to override Movie detail accents.
