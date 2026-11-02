---
name: Fire TV focus handoff
description: Android TV spatial focus behavior — pager trapping, drawer open trigger, and vertical scroll suppression
---

Keep TV navigation hidden during normal browsing so the first focus remains on page content. Open the left drawer only when D-pad Left cannot move to another content target.

**Why:** A persistent top control trapped Fire TV users away from the content and took up screen space even when navigation was not needed.

**How to apply:** Keep TV startup focus on the first available Movies card. When opened, focus the first enabled tab rather than the profile header; Up can reach the profile action intentionally and Up at the header is trapped. Up/Down traverses drawer rows; Right, Back, or Menu closes it and restores the previously focused page child. Do not scroll Movies to the top just because the drawer opens.

Nested TV rails should rely on the focusable item's normal list reveal behavior rather than adding an unconditional `BringIntoViewRequester` to every focused card. Repeated D-pad events should also be consumed after throttled focus movement, including at a group edge.

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

## HorizontalPager traps TV remote

`HorizontalPager` (Compose) intercepts **all** D-pad left/right events at the Compose input level — no `onPreviewKeyEvent` modifier on a child or parent can reliably escape it. The remote gets permanently stuck on the banner.

**Fix:** On TV, replace `HorizontalPager` with a plain `Crossfade` that auto-cycles on a timer. Make **only the action button** inside the banner focusable. Keep `HorizontalPager` for non-TV. Only attach a startup `FocusRequester` to page 0's button — `Crossfade` composes both old and new content during animation, so attaching the same requester to every page causes a duplicate-requester error.

## Avoid uninitialized directional focus targets

A reported Fire TV crash during D-pad dispatch says `FocusRequester is not initialized`. The top navigation's native `focusProperties.down` target can point to a lazy Movies hero before that target has been composed and attached.

**Fix:** Use bounded `requestFocus()` retries for navigation-to-content handoffs and drawer entry. Do not give native focus search a directional requester that may be detached during loading or route changes.

**Why:** Native focus search resolves the target synchronously and can throw before the asynchronous page target is ready.

**How to apply:** For drawer entry, retry focus on the first attached row. When the drawer closes, restore the saved NavHost child with bounded retries; preserve a safe fallback for screens without a saved child.

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

## Hero-to-first-card handoff

On TV, D-pad Down from the hero actions must focus the first actual content item, not a clickable shelf heading or a small ‘View all’ label. Keep header actions touch-clickable, but not D-pad focusable; preserve TV access through the shelf’s trailing View All card.

**Why:** Spatial focus can choose an invisible header action or lose the hero button when its details hide, leaving the viewer without a visible selection. Native focus search can also skip a shelf when the next lazy row is detached.

**How to apply:** Route Down through the hero action row to the first Continue Watching card, or the first genre card when Continue Watching is empty. Retry after layout frames because lazy content may not be attached immediately. For later Down presses, reveal the nearest later focusable row in the parent list, skipping headings; do not fall through to native focus search when a lazy target is detached. Consume D-pad repeats during the handoff and keep actual hero focus state separate from first-card focus state. Keep a TV View All card for shelves whose header action is no longer focusable.

## Wait for nested row focus targets

When a parent lazy list reveals a nested horizontal row, do not cache a missing first-card `FocusRequester` or fall back to focusing the row container during a first-card handoff. Re-read the item requester while waiting for the card to compose, then confirm focus from the card itself.

**Why:** The parent row can be visible before its first card is attached. Focusing only the row container scrolls content into view but leaves no visible selection; reusing row-level state can also misreport restored focus after leaving the first card.

**How to apply:** For explicit first-item navigation, wait for the item requester and its focus callback. Reserve row-container restoration for returning to a remembered child, not for initial navigation to a newly revealed shelf.

## Restore Up focus to a recycled card

Before requesting focus on a remembered card in a previous horizontal shelf, make sure its index is visible in that shelf's LazyRow. An attached parent shelf does not guarantee the remembered child is still composed.

**Why:** Up navigation could scroll to the previous shelf but leave no visible highlight when its previously selected card had been recycled outside the horizontal viewport.

**How to apply:** Resolve remembered indices against the current item count, reveal offscreen items, then confirm focus from the card callback. Use a card requester—not a shelf-container requester—as a static directional fallback.
