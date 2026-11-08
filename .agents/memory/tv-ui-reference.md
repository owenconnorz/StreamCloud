---
name: StreamCloud TV interface reference
description: Persistent TV navigation and visual parity choices based on user-provided reference captures.
---

On TV, keep the top navigation transparent with no enclosing black bar. Put the profile action at the left and use horizontal text destinations for Home, Search, Music, Library, Adult, and Settings. Moving focus left/right immediately opens that destination; a white pill slides to the focused tab without requiring Select. The hero artwork fills the background beneath navigation and shows the selected title, type, year, rating, synopsis, one Watch now action, and carousel position. D-pad Down enters the page's exact first focus target; Up returns through the hero to navigation. The profile chooser uses centered rectangular cards on a black background. TV search uses a remote-controlled on-screen keyboard and updates results as text is entered. Home shelves use wide 16:9 cards, and the hero artwork/details follow the focused movie or series.

Use the same TV detail hero structure for movies and series: title, type, metadata, synopsis, and vertically stacked primary actions. Keep media-specific playback controls and seasons/episodes, plus shared cast, credits, trailers, and related titles, in the page content below.

**Why:** The user-provided Prime Video reference and later correction explicitly reject any black top bar and require focus-driven horizontal navigation. The hero should show useful title information and a single playback action.

**How to apply:** Keep these behaviors consistent across TV routes. Preserve left-to-right focus and immediate navigation, and route Down/Up through the exact screen targets rather than a stale shelf. Profile cards must remain D-pad focusable; debounce TV search keystrokes without requiring a separate submit to show matches. Resume the automatic hero carousel when no shelf title is focused.
