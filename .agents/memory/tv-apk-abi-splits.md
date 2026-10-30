---
name: TV APK ABI splits
description: Compatibility and release behavior for architecture-specific StreamCloud TV APKs.
---

Keep publishing the universal TV APK as `StreamCloud-tv-release.apk` alongside ARM64 and ARMv7 APKs. The universal asset lets already-installed versions whose updater only searches for `tv-release` complete their first upgrade; newer builds can select the smaller ABI-specific asset.

**Why:** Removing the universal asset before existing users have the new selector could make the old updater choose the mobile APK or fail to find a compatible TV package. ARMv7 support is also needed for older Fire TV devices.

**How to apply:** Keep the universal asset during ABI split rollout, and preserve both ARM64 and ARMv7 variants unless the user explicitly chooses to drop older-device support.
