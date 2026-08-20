---
name: Extractor toolchain compatibility
description: Compatibility constraints for maintained YouTube extractor dependencies.
---

When updating PipePipe or BravePipe, preserve StreamCloud's Android SDK and Kotlin compiler compatibility rather than accepting their newest transitive networking runtime by default.

**Why:** Extractor releases can bring dependencies built for newer Android SDKs or Kotlin metadata versions, which makes the Android build fail before playback code can be tested.

**How to apply:** Inspect the resolved Android runtime graph after extractor changes. Keep only versions supported by the project's current compile SDK and Kotlin compiler, and use the extractor's typed API instead of older string-based filter arguments when its published signatures change.