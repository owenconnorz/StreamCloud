---
name: Android build validation
description: Environment constraint affecting how StreamCloud Android changes are compiled and validated.
---

Use the repository's GitHub Actions Android workflow as the authoritative compile check when working in this Repl; the local environment does not provide an Android SDK for Gradle.

**Why:** Local Gradle invocations fail during SDK discovery before compilation, while the existing GitHub workflow successfully assembles both Debug and Release artifacts.

**How to apply:** Keep Android source changes under `StreamCloud/`, run local source checks where possible, then use the existing build-only GitHub workflow for Android compilation. Do not turn that validation into a GitHub Release or in-app update without explicit user approval.