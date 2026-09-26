---
name: Nuvio sync contract
description: The current official Nuvio server uses separate RPCs for progress, watched titles, library items, addons, plugins, and collections.
---

The current official Nuvio sync API treats continue-watching progress, completed watched titles, and profiles as separate datasets. Library mutations use the item-specific RPC, while addons, plugins, and collections retain their own sync methods.

**Why:** A client that only syncs watch progress or continues using the retired library mutation RPC can appear to sync successfully while missing completed titles or saved library changes.

**How to apply:** When updating Nuvio account sync, compare both pull and push methods with the current official sync adapters. Keep progress fields (`content_id`, `video_id`, `position`, `duration`, `last_watched`, `progress_key`) separate from watched-item fields (`content_id`, `content_type`, `title`, `season`, `episode`, `watched_at`). Profile sync uses `sync_pull_profiles` and `sync_push_profiles` with `profile_index`; never transfer PIN hashes.

The sync service must update the same in-memory `ProfileRepository` instance that drives the UI, not only a newly constructed repository that writes preferences to disk. Nuvio library and watched records may use IMDb/provider IDs; resolve supported external IDs to TMDB IDs before inserting local TMDB-keyed entities instead of dropping them silently.

**Why:** A separate repository instance leaves the visible active profile without its newly assigned cloud index until restart, and Nuvio items with `tt...` IDs otherwise disappear even when the RPC succeeds.

**How to apply:** Obtain profile state through the application service locator during sync, and cache per-sync external-ID resolutions before writing watch progress, watched titles, or library items.

Nuvio-derived local state is isolated by Nuvio account and StreamCloud profile, including providers, repositories, addons, collections, watch history, and the Room library. Preserve old unscoped data by assigning it only to the first authenticated account/profile that claims it; never copy it into later accounts.

**Why:** A shared local store makes one Nuvio account appear to contain another account's data. Older local stores did not record the owning account, so a single first-claim migration preserves them without guessing or duplicating them across accounts.

**How to apply:** Include the authenticated user and local profile in storage keys and database selection. Claim legacy scalar profile mappings and unscoped data for the first authenticated account/profile only. Persist each explicit addon deletion before local removal, scoped to the signed-in user and selected Nuvio profile. Use an exact-count REST range as the completeness check, remove only previously synced URLs absent from a verified snapshot, and preserve a local re-add made after that snapshot. Push the replacement excluding pending tombstones, verify the complete saved URL set, then clear only confirmed tombstones. Route library deletion through the selected profile's mapped index and clear its durable delete request only after the remote row is confirmed gone.

A remote addon may remain saved in Nuvio after its manifest becomes unreachable. Treat an individual manifest fetch failure as a warning, not an account-sync failure: continue processing other addons, retain the remote URL in the baseline, and never show its query-bearing URL to the user.

**Why:** Addon availability is independent of account authentication and unrelated sync datasets; one dead endpoint should not block watch-history or library sync, and addon query strings can contain credentials.

**How to apply:** Catch manifest-install failures per addon while rethrowing coroutine cancellation. Sanitize warnings to the host and HTTP status, continue the pull, and reserve hard errors for failures reading or persisting the Nuvio snapshot.