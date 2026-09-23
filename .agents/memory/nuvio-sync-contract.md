---
name: Nuvio sync contract
description: The current official Nuvio server uses separate RPCs for progress, watched titles, library items, addons, plugins, and collections.
---

The current official Nuvio sync API treats continue-watching progress, completed watched titles, and profiles as separate datasets. Library mutations use the item-specific RPC, while addons, plugins, and collections retain their own sync methods.

**Why:** A client that only syncs watch progress or continues using the retired library mutation RPC can appear to sync successfully while missing completed titles or saved library changes.

**How to apply:** When updating Nuvio account sync, compare both pull and push methods with the current official sync adapters. Keep progress fields (`content_id`, `video_id`, `position`, `duration`, `last_watched`, `progress_key`) separate from watched-item fields (`content_id`, `content_type`, `title`, `season`, `episode`, `watched_at`). Profile sync uses `sync_pull_profiles` and `sync_push_profiles` with `profile_index`; never transfer PIN hashes.