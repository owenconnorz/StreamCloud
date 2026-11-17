---
name: Wear OS music companion
description: Pairing, browsing, and stream-safety constraints for StreamCloud's Wear OS companion.
---

The Wear OS companion is a paired controller and browser for StreamCloud's existing music library, not a separate provider-authentication client. Keep the watch and phone apps on the same application ID and release signing identity so Wear Data Layer messaging can reach the phone app.

**Why:** Wear Data Layer routing is package-scoped, while provider sessions and library access already live on the phone.

**How to apply:** Browse the phone's MediaLibrary and route supported playback to either device without copying account credentials or session cookies to the watch.

Direct watch streaming is limited to provider tracks whose audio URLs the phone can resolve without web-session headers. Keep signed URLs in memory only, send the resolver's matching user agent, and ask the phone to evict a URL rejected by the CDN. Use phone playback for providers or tracks that need private web-session state.

**Why:** Provider authentication and stream identity belong to the phone; carrying web cookies or stale signed URLs onto a watch can break playback and expose private session state.

**How to apply:** Recheck provider support and session-header requirements before adding watch playback for another source; retain phone playback as the fallback.

When switching an active track between phone and watch, transfer the supported queue and current position, pause the source while the destination prepares, and resume the source if the handoff fails.

**Why:** Starting both players at once causes overlapping audio, while stopping the source before confirming the destination can lose playback.

**How to apply:** Treat output switching as a recoverable handoff, and keep unsupported provider tracks on the phone with a clear explanation.

Keep the phone-side `WearableListenerService` registered with `BIND_LISTENER` so it can receive watch requests while the app is not foregrounded. Android lint flags this legacy registration as deprecated; suppress only `WearableBindListener` on the specific action in both manifests rather than disabling release lint.

**Why:** The bridge must receive background watch-to-phone messages, and release lint treats the old action as fatal even though the listener service still depends on it.

**How to apply:** If replacing the listener mechanism, first verify equivalent background message delivery; otherwise preserve the service and use the narrow manifest suppression.

Run phone-side Media3 `MediaBrowser` and `MediaController` operations, including browser creation and release, on `Dispatchers.Main.immediate`. Keep Wearable message transport and blocking task waits on IO.

**Why:** Media3 controllers are bound to their application looper; using them from the listener's IO coroutine throws a wrong-thread exception and can leave the watch waiting for a response.

**How to apply:** When adding Wear bridge operations, marshal the full controller transaction to Main, then return to IO before sending the Wearable response.

Create Media3 controllers from `applicationContext` when the caller is a short-lived service.

**Why:** Media3 can defer unbinding while queued session commands finish, beyond the lifetime of the service context that created the controller.

**How to apply:** Use the application context for both the `SessionToken` and controller/browser builder in listener services; let the controller own its binding cleanup.

Give the watch a dedicated Now Playing screen with artwork, track details, playback progress, and grouped transport controls; keep library browsing as a separate view.

**Why:** The user said a screen made only of skip and pause buttons is not a proper media screen.

**How to apply:** When changing Wear music UI, prioritize the active track player and make browsing available without replacing the player with a list of transport chips.

Offer watch updates from the Wear APK attached to a published GitHub Release; download to app cache and hand off installation to Android's package installer.

**Why:** The user wants to update the watch app without repeatedly sideloading it from another device, while Android still requires install confirmation for manually distributed APKs.

**How to apply:** Keep the Wear package ID and release signing identity unchanged, and do not imply that GitHub APK updates install silently.
