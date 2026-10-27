---
name: Sonos instant discovery
description: Expected picker behavior when opening the Sonos destination list.
---

Show the last successfully resolved Sonos room groups and standalone speakers immediately from a private on-device cache while SSDP refresh runs in the background. Do not briefly publish the raw SSDP list before room topology has been resolved. The first scan still requires network discovery; cached targets must be checked for reachability before playback.

**Why:** A full discovery scan makes the picker appear empty for several seconds even when the user has already found speakers on this network. Caching raw SSDP devices also makes an already-grouped room appear as several separate speakers until the topology request completes.

**How to apply:** Persist validated target identifiers, names, local IPv4 addresses, ports, and resolved group membership. Wait for the topology lookup to finish before publishing new discovery results; if lookup fails, do not overwrite an existing grouped snapshot with raw SSDP devices. Retain saved targets with a clear stale-data warning when refresh finds nothing, and ensure a late scan cannot overwrite a speaker selection or active cast.
