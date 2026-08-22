---
name: Android in-app release tags
description: GitHub release naming constraints for StreamCloud's Android in-app updater.
---

Publish each update as a public GitHub Release containing a signed APK named `StreamCloud-release.apk`. Use a release tag whose **first numeric value** is the CI run/build number used for the APK version code (for example, `V1572`).

**Why:** The Android updater reads the GitHub releases list, selects the newest release with an APK, and compares the first number in its tag with the installed version code. Conventional tags such as `V1.0.1572` parse as `1`, so they will never be offered to modern installed builds.

**How to apply:** Build from the target `main` commit in GitHub Actions, attach the release APK to a public release, and verify anonymously that the release appears first in the releases API and its tag number exceeds the prior APK's version code. Mirror a release to the legacy `AioWeb` update channel while older locally built installations may still use that fallback.