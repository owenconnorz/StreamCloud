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
