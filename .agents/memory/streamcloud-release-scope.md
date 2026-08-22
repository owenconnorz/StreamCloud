---
name: StreamCloud release scope
description: Repository boundary for StreamCloud Android release publishing
---

StreamCloud Android releases belong only to the StreamCloud repository. Do not upload, mirror, or update APK releases in AioWeb.

**Why:** AioWeb is a separate project and the owner explicitly corrected that it must not receive StreamCloud updates.

**How to apply:** Publish authorized APKs only through StreamCloud’s workflow and StreamCloud GitHub Releases. If an old AioWeb mirror exists, leave it unchanged unless the owner separately asks for cleanup.