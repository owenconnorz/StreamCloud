---
name: StreamCloud TV interface reference
description: Persistent TV navigation and visual parity choices based on user-provided reference captures.
---

On TV, keep navigation transparent and avoid a solid black top bar. Match the Prime-style rail animation in the current reference: show icons only when focus is outside the rail; when focus enters, smoothly widen the rail over the page, reveal the profile name and destination labels, and slide one rounded white focus pill between items. Collapse back to icons when focus leaves. Moving through destinations only changes the highlight; Right or click activates the destination. Keep Search accessible in the rail. The hero artwork fills the background beneath navigation and shows the selected title, type, year, rating, synopsis, one Watch now action, and carousel position. D-pad Down enters the page's exact first focus target; Up returns through the hero to navigation. The profile chooser uses centered rectangular cards on a black background. TV search uses a remote-controlled on-screen keyboard and updates results as text is entered. Home shelves use wide 16:9 cards, and the hero artwork/details follow the focused movie or series.

Use the same TV detail hero structure for movies and series: title, type, metadata, synopsis, and vertically stacked primary actions. Keep media-specific playback controls and seasons/episodes, plus shared cast, credits, trailers, and related titles, in the page content below.

**Why:** The user asked to match the Prime-style rail animation, with its labels visible only while the rail has focus and route changes committed by Right or click.

**How to apply:** Keep these behaviors consistent across TV routes. Preserve vertical focus through the rail, animated expand/collapse without moving page content, and explicit Right-to-content handoffs. Route Down/Up through the exact screen targets rather than a stale shelf. Profile cards must remain D-pad focusable; debounce TV search keystrokes without requiring a separate submit to show matches. Resume the automatic hero carousel when no shelf title is focused.
