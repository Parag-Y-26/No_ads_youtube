# DECISIONS.md — YouTube Ext App

| Date | Decision | Why |
|------|----------|-----|
| 2026-11-15 | Use WebView over ExoPlayer | Full YouTube site gives login, playlists, comments — the full experience. Extensions work via JS injection. |
| 2026-11-15 | Kotlin + Compose over Flutter | User preference. Native Android gives best WebView control. |
| 2026-11-15 | SponsorBlock via public API | Free, no auth, community-driven database of sponsor segments. |
| 2026-11-15 | JS injection over WebExtension API | Android WebView doesn't support WebExtension API. JS injection is the only viable path. |
