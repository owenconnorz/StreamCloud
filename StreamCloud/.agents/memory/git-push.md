---
name: Git push workflow
description: How to cleanly push when local branch has diverged from remote
---

**Rule:** Never `git pull --rebase` when there are untracked files that overlap with remote commits — the rebase will get stuck and `--abort` will also fail until the untracked file is manually removed.

**Why:** The Replit environment sometimes leaves untracked artefact files (e.g. `artifacts/mockup-sandbox/...`) that conflict with commits being replayed during rebase.

**How to apply:**
1. `git fetch <remote> main`
2. `git reset --hard FETCH_HEAD` (discards local diverged history, puts HEAD at remote tip)
3. Re-write any files that were lost in the reset (our changes were uncommitted or in a diverged commit)
4. `git add … && git commit -m "…"`
5. `GIT_ASKPASS=true GIT_TERMINAL_PROMPT=0 git push https://${GITHUB_PERSONAL_ACCESS_TOKEN}@github.com/owenconnorz/StreamCloud.git main`

If step 2 fails due to untracked files: `rm -f <path/to/untracked>` first, then retry reset.
