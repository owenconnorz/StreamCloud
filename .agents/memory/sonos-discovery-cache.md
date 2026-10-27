---
name: Sonos instant discovery
description: Expected picker behavior when opening the Sonos destination list.
---

Show the last successfully discovered Sonos speakers immediately from a private on-device cache while SSDP refresh runs in the background. The first scan still requires network discovery; cached devices must be checked for reachability before playback.

**Why:** A full discovery scan makes the picker appear empty for several seconds even when the user has already found speakers on this network.

**How to apply:** Persist only validated device identifiers, names, local IPv4 addresses, and ports. Replace the cache after a successful scan, retain it with a clear stale-data warning if refresh finds nothing, and ensure a late scan cannot overwrite a speaker selection or active cast.
