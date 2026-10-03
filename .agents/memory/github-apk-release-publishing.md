---
name: GitHub APK release publishing
description: Non-obvious verification and recovery details for StreamCloud APK releases
---

GitHub Actions can report a successful `softprops/action-gh-release` upload while the release API temporarily or inconsistently reports no assets. Verify the release through an authenticated GitHub CLI lookup and confirm both APK names, sizes, and download URLs before declaring it published. If the local CLI is unauthenticated, use the connected GitHub API for the same checks; never request or expose a token. If the release remains inconsistent, recreate only that release from the verified CI artifact archives.

**Why:** The release action log can say both large APK uploads succeeded while release-list API responses still show an empty asset list. Direct release recreation with the same tag and explicit APK uploads produced a consistently indexed release.

**How to apply:** Keep build validation in Actions, inspect artifact archives when needed, and use the authenticated GitHub CLI for final asset verification. If `gh` has no local session, use `listConnections("github")` and `proxyFetch` to verify the release tag, resolved commit, both APK names, sizes, and download URLs. Do not attach an older local APK as a substitute for the current CI artifact.