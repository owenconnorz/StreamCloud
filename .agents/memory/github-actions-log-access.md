---
name: GitHub Actions log access
description: Work around GitHub connector failures when downloading Actions job logs.
---

The GitHub connector may return 403 for Actions job-log downloads, but it has also returned HTTP 200 with readable plain-text job logs. Check the response status and content type instead of assuming either behavior. When available, extract only compiler/build errors; otherwise, inspect annotations and job summaries.

**Why:** Actions logs can use an archive/redirect flow, and connector behavior varies. A blocked download or generic exit-code annotation does not establish the underlying build failure's cause.

**How to apply:** Verify the run SHA, conclusion, failed job, and step. Try the job-log endpoint and inspect its status/content type; if it is readable, return only the relevant error excerpt. If blocked, use failure-level annotations without guessing from generic exit codes or unrelated cache warnings, or ask the user to share the log.