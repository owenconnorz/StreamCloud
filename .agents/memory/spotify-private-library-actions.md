---
name: Spotify private library actions
description: Blend invitation lifecycle and verification expectations for undocumented Spotify Web Player library actions.
---

Spotify Blend generation creates an invitation, not a populated Blend playlist. The Blend appears only after another account joins. Spotify playlist-folder changes use a separate undocumented Web Player rootlist operation; do not treat an HTTP success alone as proof that Spotify accepted the change.

**Why:** Calling an invitation a completed Blend or reporting an unconfirmed folder write as successful misleads users, and these private endpoints can change independently of the public Spotify API.

**How to apply:** Keep Web Player actions isolated from catalog Pathfinder calls and their cooldowns. Surface failures, require a confirmed rootlist revision/result for folder changes, and verify account state in Spotify before claiming full sync.
