---
name: GitHub Android source path
description: Repository-path rule for committing Android sources through GitHub and receiving automatic Actions builds.
---

The Android project that GitHub Actions builds is under the repository’s `StreamCloud/` directory. Commit Android source files to `StreamCloud/app/...`, not root-level `app/...`.

**Why:** The APK workflow runs from `StreamCloud/` and only listens to changes under `StreamCloud/**`; root-level source writes neither trigger the workflow nor affect the APK. Shell-command output can also truncate or differ from the current worktree snapshot when forming large file payloads.

**How to apply:** When mapping this workspace’s Android sources through the GitHub integration, keep the `StreamCloud/` prefix. Use a direct workspace file read to form Git blobs and verify its size against the checkout; do not rely on command output for large or identity-sensitive source payloads.