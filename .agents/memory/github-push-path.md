---
name: GitHub push path
description: How to publish StreamCloud commits when the local checkout exposes only internal Replit remotes
---

The local checkout may expose only internal backup/sub-Repl remotes rather than a usable GitHub origin. Publish to `owenconnorz/StreamCloud` through the installed GitHub connection API, and verify the remote `main` SHA and parent before updating the ref.

**Why:** The internal backup remote can reject pushes or advertise incomplete history, while the GitHub connection can update the real repository without putting credentials in Git config.

**How to apply:** Check the GitHub `main` tip first, ensure the intended commit is based on it, create the commit through GitHub’s Git Data API, update `refs/heads/main` without force, and then verify the resulting Actions run.