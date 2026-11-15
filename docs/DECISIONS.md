# DECISIONS.md — YouTube Ext App

| Date | Decision | Why |
|------|----------|-----|
| 2026-11-15 | Use WebView over ExoPlayer | Full YouTube site gives login, playlists, comments — the full experience. Extensions work via JS injection. |
| 2026-11-15 | Kotlin + Compose over Flutter | User preference. Native Android gives best WebView control. |
| 2026-11-15 | SponsorBlock via public API | Free, no auth, community-driven database of sponsor segments. |
| 2026-11-15 | JS injection over WebExtension API | Android WebView doesn't support WebExtension API. JS injection is the only viable path. |
| 2026-11-15 | Toolbar download button | Provides a reliable native top-bar trigger for video downloading alongside the in-page button. |
| 2026-11-15 | Custom branding "No_ads_Tube" | Adaptive mipmap icon generated with custom unlocked lock and play motif across all standard Android densities. |
