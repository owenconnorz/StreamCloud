---
name: Nuvio avatar ID compatibility
description: Preserve valid Nuvio profile avatar IDs when changing StreamCloud's local avatar picker.
---

Profile avatar seeds are sent to Nuvio as `avatar_id` values and resolved against Nuvio's avatar catalog. Newly selected built-in avatars should use the catalog's actual IDs. Keep an existing unknown seed unchanged when editing unrelated profile settings.

**Why:** A locally invented ID can display from a bundled image in StreamCloud but will not resolve to artwork in Nuvio's cloud profile.

**How to apply:** Before changing the local avatar catalog or sync behavior, verify IDs against Nuvio's public catalog and preserve unrecognized stored values unless the user selects a replacement.