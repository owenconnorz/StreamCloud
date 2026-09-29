---
name: Nuvio sync contract
description: The current official Nuvio server uses separate RPCs for progress, watched titles, library items, addons, plugins, and collections.
---

The current official Nuvio sync API treats continue-watching progress, completed watched titles, profiles, and saved-library items as separate datasets. Library push uses `sync_push_library`; library deletion uses authenticated REST deletes; collection push uses `p_collections_json`.

**Why:** A client that only syncs watch progress or uses retired library RPC names/arguments can appear to sync successfully while missing completed titles or saved-library changes.

**How to apply:** When updating Nuvio account sync, compare both pull and push methods with the current official sync adapters. Keep progress fields (`content_id`, `video_id`, `position`, `duration`, `last_watched`, `progress_key`) separate from watched-item fields (`content_id`, `content_type`, `title`, `season`, `episode`, `watched_at`). Push RPCs require `p_origin_client_id`; profile sync uses `sync_pull_profiles` and `sync_push_profiles` with `profile_index`; never transfer PIN hashes.

The sync service must update the same in-memory `ProfileRepository` instance that drives the UI, not only a newly constructed repository that writes preferences to disk. Nuvio library and watched records may use IMDb/provider IDs; resolve supported external IDs to TMDB IDs before inserting local TMDB-keyed entities instead of dropping them silently.

**Why:** A separate repository instance leaves the visible active profile without its newly assigned cloud index until restart, and Nuvio items with `tt...` IDs otherwise disappear even when the RPC succeeds.

**How to apply:** Obtain profile state through the application service locator during sync, and cache per-sync external-ID resolutions before writing watch progress, watched titles, or library items.

Per-episode watched state is separate from the parent-level watched movie/series row. Use the show TMDB ID plus numeric season and episode as the local identity, and map it to the Nuvio watched-items `season` and `episode` fields rather than synthetic IDs or title suffixes.

**Why:** Parent-level completion and episode completion are different facts; synthetic IDs can no longer resolve to the show's TMDB metadata.

**How to apply:** Read and write episode records through the active profile's `LibraryDb`, preserve parent watched-title sync, and request account sync after local episode completion.

Continue-Watching progress also needs an episode identity: keep the show TMDB ID and distinguish rows by media type, season, and episode. Send the same episode key in `video_id` and `progress_key`; persist delete tombstones and apply them before pulling remote progress.

**Why:** A show-only progress key overwrites one episode with another, and pulling before a pending delete recreates items the user removed.

**How to apply:** Use the composite local identity and `sync_delete_watch_progress` before account pulls. Keep the movie key at the show/movie TMDB ID when no episode numbers are present.

Home-layout preferences use the dedicated home-catalog-settings RPCs on a separate `streamcloud` platform row, with StreamCloud preferences inside a `streamcloud` JSON namespace. Do not reuse the native `android` platform row.

**Why:** Platform rows are isolated, and overwriting the Android row risks replacing Nuvio's own layout. The public schema accepts JSON settings but does not establish whether Nuvio's official app reads StreamCloud-specific keys.

**How to apply:** Pull and push only the StreamCloud namespace under the `streamcloud` platform. Do not claim official Nuvio-app compatibility without validating it against an authorized account.

Nuvio-derived local state is isolated by Nuvio account and StreamCloud profile, including providers, repositories, addons, collections, watch history, and the Room library. Preserve old unscoped data by assigning it only to the first authenticated account/profile that claims it; never copy it into later accounts.

**Why:** A shared local store makes one Nuvio account appear to contain another account's data. Older local stores did not record the owning account, so a single first-claim migration preserves them without guessing or duplicating them across accounts.

**How to apply:** Include the authenticated user and local profile in storage keys and database selection. Claim legacy scalar profile mappings and unscoped data for the first authenticated account/profile only. Persist each explicit addon deletion before local removal, scoped to the signed-in user and selected Nuvio profile. Use an exact-count REST range as the completeness check, remove only previously synced URLs absent from a verified snapshot, and preserve a local re-add made after that snapshot. Push the replacement excluding pending tombstones, verify the complete saved URL set, then clear only confirmed tombstones. Route library deletion through the selected profile's mapped index and clear its durable delete request only after the remote row is confirmed gone.

A remote addon may remain saved in Nuvio after its manifest becomes unreachable. Treat an individual manifest fetch failure as a warning, not an account-sync failure: continue processing other addons, retain the remote URL in the baseline, and never show its query-bearing URL to the user.

**Why:** Addon availability is independent of account authentication and unrelated sync datasets; one dead endpoint should not block watch-history or library sync, and addon query strings can contain credentials.

**How to apply:** Catch manifest-install failures per addon while rethrowing coroutine cancellation. Sanitize warnings to the host and HTTP status, continue the pull, and reserve hard errors for failures reading or persisting the Nuvio snapshot.