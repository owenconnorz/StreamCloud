---
name: Spotify private library actions
description: Spotube-style playlist creation, collaboration boundaries, and verification rules for undocumented Spotify Web Player actions.
---

Spotify Blend generation creates an invitation, not a populated Blend playlist. The Blend appears only after another account joins. Spotify playlist-folder changes use a separate undocumented Web Player rootlist operation; do not treat an HTTP success alone as proof that Spotify accepted the change.

Regular playlist creation may use Spotube's Web Player create operation followed by a Pathfinder rootlist add and a confirmed private-visibility update. Collaborative playlist creation must remain on Spotify's supported REST route: Spotube's current implementation does not apply its collaboration flag. Keep REST, Web Player, and Pathfinder cooldowns independent, and retain enough pending state to resume a partially completed regular-playlist setup without creating a duplicate.

**Why:** The user chose to use Spotube's regular-playlist flow while preserving real collaborative semantics. Treating a normal playlist as collaborative or silently retrying another endpoint after a 429 would misrepresent account state and can create duplicates.

**How to apply:** Do not use the regular-playlist route as a fallback after a REST 429; it is the primary path for ordinary playlists. Surface partial-sync failures, resume them by playlist identity, require Spotify confirmations for rootlist/visibility changes, and keep the collaborative action on REST with its own cooldown.

When a track is selected from Spotify and played through a matched YouTube Music stream, preserve its Spotify track identity for player actions. The player’s “Add to playlist” action must show Spotify playlists and add the Spotify track, not save the matched YouTube item to YouTube Music.

**Why:** Spotify-origin tracks carry a Spotify ID alongside the matched YouTube stream ID; treating the latter as the source sends the user to the wrong account’s library.

**How to apply:** Route player playlist actions by the Spotify track identity stored in media metadata, while keeping ordinary YouTube Music tracks on the existing YouTube Music picker.
