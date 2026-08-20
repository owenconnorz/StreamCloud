---
name: Android KAPT source isolation
description: Handling CI-only KAPT duplicate-class failures while adding Android provider logic.
---

When KAPT reports duplicate classes across established models after an otherwise focused provider change, keep new request/response models and resolver logic in a dedicated source file instead of extending those model-heavy files.

**Why:** KAPT can produce broad duplicate-stub failures for existing classes while processing a modified, annotation-heavy source file, even when the source tree itself has no duplicate declaration.

**How to apply:** If this appears after editing established API or view-model models, restore their prior shape, move provider-specific types and parsing into a narrowly scoped resolver file, and verify the Android CI build rather than renaming unrelated existing models.