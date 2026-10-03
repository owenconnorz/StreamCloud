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

Workspace edits may also appear as separate local commits authored by Replit Agent, even when the requested publication must use Owen's GitHub identity.

**Why:** Pushing those automatic commits would violate the repository owner's attribution preference.

**How to apply:** Before publishing, inspect every unpublished commit. Fold agent-authored workspace commits into the intended Owen-authored commit, verify the parent and changed files, and push only after the identity is correct.

Git Database commit responses expose raw author and committer metadata; in repository commit responses, linked accounts are top-level `author` and `committer`, while raw metadata stays under `commit`.

**Why:** Checking a Git Database response for `author.login` or `committer.login` can incorrectly reject a commit with the right author metadata.

**How to apply:** Before updating a branch ref, fetch `/repos/{owner}/{repo}/commits/{sha}` and verify both linked logins match the expected account. Update refs with `force: false`.

A GitHub Contents API read for a workflow file has returned a Cloudflare 403 even when Git tree access worked. That response alone is not evidence that GitHub permissions are missing.

**Why:** A workflow-path read can be blocked by an intermediary before GitHub returns its API response.

**How to apply:** For blocked reads, locate the workflow file in the verified commit tree and fetch its blob by SHA. For writes under `.github/workflows`, still confirm the provider-declared `workflow` scope is available.

GitHub GraphQL `createCommitOnBranch` may link the author to the authenticated account while assigning the committer to `web-flow`. That is GitHub's service identity, not the account's linked committer identity.

**Why:** An API commit can therefore have the correct author login but fail a repository policy that requires both author and committer to match the owner.

**How to apply:** Check both logins through the standard repository commits endpoint before moving a branch ref. Do not assume GraphQL preserves the owner's committer identity; when both must match, use a Git Database commit only with an email GitHub verifies as belonging to that account, and keep the ref update non-forced.

The CodeExecution shell callback can normalize line endings and truncate large combined outputs, especially base64 asset listings. Do not use its text verbatim as a Git blob or merge base.

**Why:** A line-ending transformation can create false merge conflicts, and an incomplete encoded listing can omit assets from a commit.

**How to apply:** Read text from the workspace directly and compare it with the remote blob. Encode binary files in small batches, check the truncation flag, and verify every expected path and count before creating Git blobs.