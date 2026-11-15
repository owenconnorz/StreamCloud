---
name: StreamCloud TV interface reference
description: Persistent TV navigation and visual parity choices based on user-provided reference captures.
---

Use the user-provided Behance Movie Streaming UI Kit as the visual reference across mobile and TV: dark cinematic surfaces, artwork-led home and detail pages, clear discovery sections, and recognizable poster shapes. Keep media metadata truthful. Adapt the mobile composition for TV instead of shrinking it; preserve remote-friendly navigation and focus behavior with wide artwork cards and explicit D-pad handoffs.

Android TV uses a compact, translucent horizontal top bar matching the user-provided Peacock reference. Keep StreamCloud's real destinations, but omit the app name and logo from the bar. Use the selected app palette's primary color for navigation focus instead of fixed yellow. Moving through destinations only changes the highlight; click or D-pad Down activates the selected destination. Search and profile focus must highlight those controls rather than leaving the selected destination highlighted.

On Movies home, show a random featured recommendation while the top navigation has focus. When a shelf item is focused, use that movie or episode's artwork as the hero background without repeating its title or actions. Keep featured actions just above Continue Watching, and hide them for Continue Watching titles. Resume the automatic hero carousel when no shelf item is focused. Use wide 16:9 home cards. TV search uses a remote-controlled on-screen keyboard and updates results as text is entered. Keep the profile chooser's centered, D-pad-focusable cards on its dark background.

Use the same TV detail hero structure for movies and series: title, type, metadata, synopsis, and vertically stacked primary actions. Keep media-specific playback controls and seasons/episodes, plus shared cast, credits, trailers, and related titles, in the page content below.

Run the welcome, optional Nuvio sign-in, profile/avatar, and recommendation-preference flow only during first setup. Keep later profile switching quick, and store genre preferences per local profile so each profile can prioritize matching home picks.

**Why:** The user selected the Behance kit for the app's visual direction, chose the supplied Peacock-style top bar over a left rail, and specified palette-matched focus, no branding inside the bar, and a hero that responds to navigation versus shelf focus. They also chose first-run-only onboarding so returning viewers can switch profiles without repeating setup.

**How to apply:** Adapt the Behance visual language for TV without carrying over Prime-specific styling. Preserve click/Down-to-activate behavior and explicit D-pad handoffs to real screen content. Use the current palette accent for focus. Keep the default hero distinct from Continue Watching where possible, and switch to artwork-only when a shelf card is focused. Treat Nuvio login as optional, reuse its existing email/password session, and do not show onboarding again after setup is complete.
