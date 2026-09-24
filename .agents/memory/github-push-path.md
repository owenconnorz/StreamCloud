---
name: GitHub push path
description: How to publish StreamCloud commits when the local checkout exposes only internal Replit remotes
---

The local checkout may expose only internal backup/sub-Repl remotes rather than a usable GitHub origin. Publish to `owenconnorz/StreamCloud` through the installed GitHub connection API, and verify the remote `main` SHA and parent before updating the ref.

**Why:** The internal backup remote can reject pushes or advertise incomplete history, while the GitHub connection can update the real repository without putting credentials in Git config.

**How to apply:** Check the GitHub `main` tip first. If local and remote histories diverged, review and overlay only the intended source changes onto that tip; do not push the local branch or replace whole files with stale copies. Update `refs/heads/main` without force, then verify the remote SHA and Actions run.

The GitHub Contents API requires both `repo` and the separate `workflow` OAuth scope to modify files under `.github/workflows`. A connection can report repository admin/push access while still lacking this scope.

**Why:** Publishing source changes without the related workflow update can leave the intended CI tests unrun, while partially updating `main` is unsafe.

**How to apply:** Before publishing workflow changes, check that the provider-declared reauthorization scopes include `workflow`. If not, stop before merging and ask for a supported authorization path.