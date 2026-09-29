---
name: Playback sync priority
description: Keep frequent watch-progress snapshots from delaying a completed watched-state update.
---

Routine playback-position snapshots are replaceable; watched-state completion and explicit account mutations are not. Coalesce progress-only work in the existing unique sync chain, but append completion and other durable changes so they still run after an active sync. Refresh cloud state when the mobile app returns to the foreground, even when its profile has not changed.

**Why:** Frequent progress snapshots previously appended work and could leave a completed episode waiting behind stale snapshots. Using a coalescing policy for every request would instead risk dropping a completion that arrives while a sync is active.

**How to apply:** Use the coalesced path only for periodic or disposal-time playback-position saves. Keep completion and explicit changes on the guaranteed follow-up path, and retain the cold-start/profile-change sync alongside foreground refresh.