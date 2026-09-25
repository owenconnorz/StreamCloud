---
name: GitHub Actions log access
description: Work around GitHub connector failures when downloading Actions job logs.
---

In this workspace, the connected GitHub API returned 403 for Actions job-log downloads even though run status, job summaries, check runs, and annotations were readable. The annotation only said the process exited with code 1; Gradle cache warnings did not establish the build failure's cause.

**Why:** Actions log downloads use an archive/redirect flow that may be blocked by the connector even when ordinary GitHub REST reads work. A 403 on that endpoint is not proof that the underlying Android build failure is an authorization issue or a specific source-code problem.

**How to apply:** Verify the run SHA, overall conclusion, failed job and step, and failure-level annotations through the JSON API. Do not infer a root cause from a generic exit-code annotation or unrelated warnings. If the exact Gradle output is needed, use another authorized way to retrieve the logs or ask the user to share them.