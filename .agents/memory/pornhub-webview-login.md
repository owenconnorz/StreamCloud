---
name: Pornhub WebView login
description: Navigation security boundary for the embedded Pornhub login flow.
---

Keep top-level login navigation allowlisted to Pornhub and supported identity providers, but allow HTTPS subframes from other hosts. CAPTCHA, anti-bot verification, and identity widgets may run in third-party frames; blocking them can make the login form appear unresponsive.

**Why:** A host allowlist applied to every WebView navigation request can silently prevent required embedded verification from loading, even while the main login page renders normally.

**How to apply:** Use `WebResourceRequest.isForMainFrame` to distinguish top-level navigation from subframes. Enforce the host allowlist on top-level requests and allow only HTTPS for third-party subframes.