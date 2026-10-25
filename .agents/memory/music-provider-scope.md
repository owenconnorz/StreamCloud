---
name: Independent music providers
description: User-approved scope and compatibility boundary for music-provider work.
---
Use an independent StreamCloud music-provider system, not Echo plugin compatibility.

**Why:** The user chose an independent implementation after discussing Echo's licence, and asked to add the music-home switcher first, then Spotify and a separate GitHub plugin repository afterward.

**How to apply:** Preserve existing Spotify login for later compatible integration. Do not advertise Echo extension support or show unimplemented providers as working. Publishing a separate plugin repository does not remove third-party licence obligations.

**Repository opt-in:** A fresh StreamCloud install must start with no third-party music repositories configured. Do not contact or display the provider catalogue until the user explicitly adds its GitHub link. After adding a link, it may refresh on later visits. Only HTTPS GitHub repository/catalog links are accepted; repository definitions are declarative and app-shipped adapters remain allowlisted.

**Why:** The user explicitly wants the first-run experience to show no plugins until the user adds the repository URL.

**How to apply:** Keep the default repository list empty in DataStore, show an Add repository URL action, and fetch only user-added catalogues.

**APK extension boundary:** Keep the URL-based declarative repository and app-shipped provider adapters for now. Do not move to downloadable executable APK plugins unless the user later identifies enough benefit to revisit the decision.

**Why:** The user wants to stay with the current approach until they see more benefits to an APK-based plugin system.

**How to apply:** Treat APK plugin support as a future option, not current scope. Continue fetching signed-in users' live Spotify data through the in-app adapter.

**Provider library selection:** The selected music provider also controls which account library is displayed; do not mix Spotify and YouTube Music account collections.

**Why:** The user asked for the Library to switch to Spotify's library when Spotify is selected.

**How to apply:** Keep provider-specific playlists, saved albums, followed artists, and saved tracks under the matching selected provider.

**Spotify Home and Library boundary:** Spotify Home is a personalized discovery feed; account collections belong only in the Library screen.

**Why:** The user asked for Spotify Home to resemble its discovery feed and keep their playlists and library on the Library page.

**How to apply:** Build Home from listening-based content such as top artists, top tracks, and recent plays. Do not reuse the Spotify account-library screen as the provider Home.

**Spotify adapter licensing:** Keep Spotify metadata integration as StreamCloud-owned code; do not copy Spotube's AGPL-3.0 implementation into the app without an explicit license decision.

**Why:** The user chose an independent StreamCloud adapter after discussing the license consequences.

**How to apply:** Keep the app's adapter independent and ask before adopting Spotube's library or source code.
