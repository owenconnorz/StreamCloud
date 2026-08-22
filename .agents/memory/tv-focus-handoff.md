---
name: Fire TV focus handoff
description: Android TV spatial focus behavior — pager trapping, drawer open trigger, and vertical scroll suppression
---

Fire TV spatial focus search is unreliable when a persistent overlay navigation button is the only initially focused control. Down/Right must move directly to a real, visible page control; an invisible focus bridge can become a dead end instead of a useful handoff.

**Why:** Fire Cube navigation could remain trapped on the top-left hamburger even though page controls were visible, and a hidden intermediary merely moved the trap. Touch-only clickable cards did not consistently participate in TV focus search.

**How to apply:** Keep the TV hamburger accessible, but have Down/Right invoke directional focus movement from it. Interactive TV cards and rows must be explicit focus targets with a visible focus border, so the shared traversal works across Movies, Music, Library, and other pages.

Nested TV rails should rely on the focusable item's normal list reveal behavior rather than adding an unconditional `BringIntoViewRequester` to every focused card. Repeated D-pad events should also be consumed after throttled focus movement, including at a group edge.

**Why:** A second reveal request made horizontal movement in a nested rail scroll its parent vertically, while unconsumed repeat events let focus search and list scrolling run together.

**How to apply:** Keep focus animation lightweight, use `focusGroup()` for rail traversal, and let the first D-pad press use normal spatial search while throttled repeats are handled exclusively by the focus manager.

For the Nuvio-style home flow, initial TV focus belongs on the first available movie card, not the corner launcher. Pressing Left from the first movie card opens the drawer directly; the drawer begins with Home and its profile header is informational rather than the default focus target.

**Why:** Starting on a persistent hamburger trapped remote users at the top of the screen and required an extra navigation step before they could browse.

**How to apply:** Keep the corner control available for touch, but non-focusable while the drawer is closed. Restore the first movie card after closing Home's drawer. The Left-to-open-drawer trigger gates on `firstMovieFocused` state: the first Left press moves focus toward the first film naturally; only when that card is already focused does the next Left open the drawer.

## HorizontalPager traps TV remote

`HorizontalPager` (Compose) intercepts **all** D-pad left/right events at the Compose input level — no `onPreviewKeyEvent` modifier on a child or parent can reliably escape it. The remote gets permanently stuck on the banner.

**Why:** The pager's internal scroll handling captures directional events before focus traversal can act on them.

**How to apply:** On TV, replace `HorizontalPager` with a plain `Crossfade` that auto-cycles on a timer (`LaunchedEffect` + `delay(6_000)`, `currentPage = (currentPage + 1) % items.size`). Make **only the action button** inside the banner focusable (via `tvFocusBorder` which calls `.focusable()`). The outer slide `Box` must not carry `tvFocusBorder` or `clickable` on TV — it is a visual container only. D-pad then moves freely in all four directions from the button. Keep `HorizontalPager` for non-TV (mobile/tablet) where swipe is the expected interaction. Only attach a startup `FocusRequester` to page 0's button — `Crossfade` composes both old and new content during animation, so attaching the same requester to every page causes a duplicate-requester error.

## Kotlin scope collision: key-event lambda inside LazyListScope

Placing an `onPreviewKeyEvent { event -> ... event.type ... event.key ... }` lambda **inside the content block of a `LazyColumn`** causes Kotlin to resolve `type` and `key` against the `LazyListScope` DSL (which has its own `key(...)` function and no `type` property) instead of the `KeyEvent` receiver. This results in `Unresolved reference 'type'`, `Function invocation 'key(...)' expected`, and `Incompatible types 'kotlin.Int' and Key` compiler errors.

**Why:** The `LazyListScope` content lambda is not a `@Composable` scope; Kotlin resolves identifiers against the enclosing DSL receiver, not the event parameter.

**How to apply:** Never place raw key-event logic inside a `LazyColumn` or `LazyRow` content block. Put `onPreviewKeyEvent` on the surrounding screen `Box`/`Column`, or use the shared `tvDpadRepeatThrottle()` modifier which is declared outside any list scope.
