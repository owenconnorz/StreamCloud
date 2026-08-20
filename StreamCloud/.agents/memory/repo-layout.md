---
name: Repo layout
description: Where files live in the StreamCloud repo and how to push
---

- Android source root: `StreamCloud/app/src/main/java/com/streamcloud/app/`
- Working directory for gradle/git: `StreamCloud/`
- Remote: `https://github.com/owenconnorz/StreamCloud.git`, branch `main`
- Push token: `GITHUB_PERSONAL_ACCESS_TOKEN` secret (ghp_… prefix)
- CI: GitHub Actions `build-apk.yml` — triggers on any push to `main`, produces APK artifact
- DB version: **15** (bumped from 14 for collections work). Next schema change → bump to 16 with migration.
