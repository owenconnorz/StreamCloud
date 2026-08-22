---
name: Personalized DJ privacy
description: Boundaries for using local listening signals in StreamCloud’s personalized DJ.
---

Personalized DJ mixes may select seeds from local likes, plays, and searches, but must not upload a listening profile or send seed track metadata to optional AI services. External music searches may use song, artist, or search hints only to discover playable results, and the user-facing flow must say so.

**Why:** A spoken introduction is not a sufficient reason to disclose a listener’s private listening activity to a remote AI provider. The recommendation flow should remain useful without adding a profile-sharing dependency.

**How to apply:** Keep personalized narration local unless a future feature adds clear, explicit opt-in. Preserve the distinction between local preference selection and the bounded external searches needed to find fresh tracks.