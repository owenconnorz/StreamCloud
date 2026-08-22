---
name: Sonos proxy preflight
description: Requirements for handing a signed remote audio stream to Sonos through the Android local proxy.
---

Preflight a signed audio URL with the resolver’s client identity using a tiny byte-range GET before Sonos receives the local proxy URI. Publish the upstream MIME type, complete content length, and range capability exactly as verified.

**Why:** A fabricated successful HEAD response lets Sonos accept the metadata but fail on its first media request, which appears as an active cast stuck at 0:00 with no audible playback.

**How to apply:** Derive the full length from `Content-Range` for 206 responses, not the partial `Content-Length`. Do not advertise ranges unless the source honored the range probe, and surface later upstream connection or read failures to the cast state.

If the first live Sonos request gets a CDN 403 or 416 after preflight succeeds, resolve one fresh stream and retry once before ending the cast.

**Why:** Signed media URLs can be invalidated or rejected for a later range request even though the phone's immediate preflight was accepted. A bounded refresh recovers transient failures without masking persistent source errors or retrying indefinitely.

**How to apply:** Preserve the resolver-specific request identity, replace the active source only for the fresh retry, and report the final failure if that retry is also rejected.

Prefer an independently extractor-validated audio stream for Sonos, and use identity content encoding for both the preflight and live proxy request.

**Why:** Some InnerTube URLs accept a tiny preflight but reject the later, long-lived Sonos range request. The maintained extractor already validates a real media range and is more reliable for the speaker proxy.

**How to apply:** Treat the standard resolver as fallback only, preserve the selected extractor's user agent, and never let OkHttp transparently alter the encoded media response.

When replacing a Sonos track, serialize proxy/URI/play commands with pause and resume commands, and make every replacement generation-aware.

**Why:** Stream preparation and Sonos SOAP calls can block after a newer skip or pause is requested. Cancellation alone cannot prevent a stale command from becoming the final speaker state.

**How to apply:** Give each replacement a monotonic generation, abandon superseded work at blocking boundaries, and run transport commands through one ordered lane so the most recently requested pause or resume is the final device action.