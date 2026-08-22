---
name: Remote cast transition ownership
description: Lifecycle rule for network music casting transitions and local proxy ownership.
---

Remote music-destination transitions must be serialized, and a Google Cast connection must retain
the exact session delivered by the framework’s start/resume callback until it is either promoted to
the active destination or explicitly cleaned up. Do not load or stop whichever Cast session happens
to be current.

**Why:** Route selection updates before the Cast framework necessarily finishes ending an old
session or starting a new one. A generic current-session lookup can therefore load media on, or
tear down, the wrong receiver. The app’s local cast proxy is shared, so overlapping transitions can
also stop a newly prepared stream.

**How to apply:** Any new Cast, DLNA, Sonos, or Bluetooth handoff must use the shared transition
coordinator. Keep signed, header-bound audio behind the local proxy; treat proxy startup failure as
a failed cast rather than exposing the upstream URL. Resume phone playback only after a Bluetooth
route is successfully selected or an explicit user disconnect requests it.