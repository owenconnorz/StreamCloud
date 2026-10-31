---
name: Plugin management UI references
description: The visual baseline for CloudStream, Nuvio, and Stremio plugin and add-on management screens.
---

Use the user's supplied CloudStream repository-management, repository-detail, and add-repository-dialog screenshots as the shared visual baseline for CloudStream, Nuvio, and Stremio management pages. Keep mobile and TV layouts functional, and preserve the same hierarchy and styling across repository lists, install pages, and add forms.

**Why:** the user repeated that the existing plugin and add-on screens did not match the supplied references and asked for the same treatment across providers.

**How to apply:** when changing these screens, compare all three surfaces to the supplied references and verify category filtering, repository navigation, add, install, and remove actions remain usable on mobile and TV.

Bundle official Stremio and Nuvio app icons locally for the ecosystem hub cards instead of relying on remote logo URLs.

**Why:** the previous Stremio and Nuvio logo URLs returned 404s, so their cards showed generic fallback icons.

**How to apply:** keep the branded hub icons as Android drawable resources; leave repository-provided logos and CloudStream's separate artwork loading behavior unchanged.
