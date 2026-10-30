---
name: Stremio adult home genre
description: Product requirement for the Movies home 18+ genre
---

The 18+ genre on Movies home should aggregate adult catalogs from every enabled Stremio add-on, not just the first match, and must not open the separate Adult sources hub. Keep it controlled by the adult-content setting and existing age-confirmation gate.

**Why:** The user explicitly wants adult Stremio content grouped with regular movie genres instead of being redirected to the adult-source page, and expects all installed add-ons with adult content to contribute.

**How to apply:** Discover explicit adult catalogs from manifests without waiting for full home rows, collect every enabled adult catalog across add-ons, preserve the 18+ setting and age gate, and defer full catalog-content fetching until after primary Movies content.
