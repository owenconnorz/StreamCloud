# Third-party notices

StreamCloud is licensed under GPL-3.0-or-later. Its SC Engine torrent integration
is based on Nuvio Engine Android 0.1.6, also licensed under GPL-3.0-or-later.

Nuvio Engine sources are fetched at build time from the pinned upstream commit
`167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3`; its Android release AAR is
verified by SHA-256 before its native libraries are extracted. See
`StreamCloud/app/build.gradle.kts` for the pinned source blob hashes and AAR
checksum.

The bundled Nuvio Engine native library includes:

- libtorrent 2.0.12 — BSD 3-Clause
- Boost 1.86.0 — Boost Software License 1.0
- OpenSSL 3.5.7 — Apache License 2.0
- try_signal — see the bundled upstream license notice

The full GPL, engine, libtorrent, Boost, OpenSSL, and try_signal license texts
are packaged in the app under `licenses/` and can be opened from Settings →
About → Open-source licenses. Nuvio Engine source:  
https://github.com/NuvioMedia/nuvio-engine/tree/167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3
