---
name: Android build validation
description: Environment constraint affecting how StreamCloud Android changes are compiled and validated.
---

Use the repository's GitHub Actions Android workflow as the authoritative compile check when working in this Repl; the local environment does not provide an Android SDK for Gradle. ABI-split APK packaging with large native libraries can also exceed the 5 GB Gradle heap when packaging workers overlap, so keep Gradle worker concurrency constrained.

**Why:** Local Gradle invocations fail during SDK discovery before compilation. A mobile release CI run later exhausted Java heap in `PackageAndroidArtifact$IncrementalSplitterRunnable` while packaging ABI splits.

**How to apply:** Keep Android source changes under `StreamCloud/`, run local source checks where possible, and use the existing build-only GitHub workflow for Android compilation. Keep Gradle workers limited for CI packaging. Do not turn that validation into a GitHub Release or in-app update without explicit user approval.