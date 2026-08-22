---
name: Fire TV focus handoff
description: Android TV spatial focus behavior — pager trapping, drawer open trigger, and vertical scroll suppression
---

Fire TV spatial focus search is unreliable when a persistent overlay navigation button is the only initially focused control. Down/Right must move directly to a real, visible page control; an invisible focus bridge can become a dead end instead of a useful handoff.

**Why:** Fire Cube navigation could remain trapped on the top-left hamburger even though page controls were visible, and a hidden intermediary merely moved the trap. Touch-only clickable cards did not consistently participate in TV focus search.

**How to apply:** Keep the TV hamburger accessible, but have Down/Right invoke directional focus movement from it. Interactive TV cards and rows must be explicit focus targets with a visible focus border, so the shared traversal works across Movies, Music, Library, and other pages.

Nested TV rails should rely on the focusable item's normal list reveal behavior rather than adding an unconditional `BringIntoViewRequester` to every focused card. Repeated D-pad events should also be consumed after throttled focus movement, including at a group edge.

For the Nuvio-style home flow, initial TV focus belongs on the first available movie card, not the corner launcher. Pressing Left from the first movie card opens the drawer directly; the drawer begins with Home and its profile header is informational rather than the default focus target.

**Why:** Starting on a persistent hamburger trapped remote users at the top of the screen and required an extra navigation step before they could browse.

**How to apply:** Keep the corner control available for touch, but non-focusable while the drawer is closed. Restore the first movie card after closing Home's drawer.

## Startup focus timing fix

`requestFocus()` on a `LazyColumn` item fails silently when called immediately on `LaunchedEffect(startupFocusTarget)` — the item hasn't been laid out yet. The fix is a retry loop:

```kotlin
repeat(10) {
    delay(200L)
    val ok = runCatching { initialFocusRequester.requestFocus() }.isSuccess
    if (ok) { startupFocusRequested = true; return@LaunchedEffect }
}
```

**Why:** The LazyColumn item is enqueued but not yet measured/placed when the effect fires. `runCatching` swallows the exception so `startupFocusRequested` stays false, but the effect never retries because its keys haven't changed.

**How to apply:** Always retry focus requests for items inside lazy containers. Do not gate on `startupFocusRequested` inside the retry loop — only set it on success.

## Corner launcher must be truly non-focusable when drawer is closed

`focusProperties { canFocus = false }` alone is insufficient to stop Android TV's native focus engine from landing on the corner launcher. The launcher also carries `.tvFocusBorder()` which internally calls `.focusable()`, overriding the property.

**Fix:** Apply both `tvFocusBorder` and `focusable` conditionally:
```kotlin
.then(if (tvNavOpen) Modifier.tvFocusBorder(CircleShape) else Modifier.focusable(false))
```
`Modifier.focusable(false)` requires `import androidx.compose.foundation.focusable`.

**Why:** Compose's `focusProperties { canFocus }` sets a preference on the node, but a co-located `.focusable()` modifier from `tvFocusBorder` creates a focusable node regardless. The native TV focus engine sees the focusable node before Compose enforces the property.

## HorizontalPager traps TV remote

`HorizontalPager` (Compose) intercepts **all** D-pad left/right events at the Compose input level — no `onPreviewKeyEvent` modifier on a child or parent can reliably escape it. The remote gets permanently stuck on the banner.

**Fix:** On TV, replace `HorizontalPager` with a plain `Crossfade` that auto-cycles on a timer. Make **only the action button** inside the banner focusable. Keep `HorizontalPager` for non-TV. Only attach a startup `FocusRequester` to page 0's button — `Crossfade` composes both old and new content during animation, so attaching the same requester to every page causes a duplicate-requester error.

## Kotlin scope collision: key-event lambda inside LazyListScope

Placing an `onPreviewKeyEvent { event -> ... }` lambda inside the content block of a `LazyColumn` causes Kotlin to resolve `type` and `key` against the `LazyListScope` DSL, not the `KeyEvent` receiver.

**Fix:** Never place raw key-event logic inside a `LazyColumn` or `LazyRow` content block. Put `onPreviewKeyEvent` on the surrounding screen `Box`/`Column`, or use the shared `tvDpadRepeatThrottle()` modifier.

## .let {} is not @Composable — use if blocks for nullable state in Column

Calling `someNullable?.let { value -> Composable(...) }` inside a Compose `Column` lambda fails to compile because the `.let` lambda is not annotated `@Composable`.

**Fix:**
```kotlin
val value = someNullable
if (value != null) {
    Composable(value.field)
}
```
