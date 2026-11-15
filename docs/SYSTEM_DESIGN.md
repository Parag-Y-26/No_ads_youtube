# SYSTEM_DESIGN.md — YouTube Ext App

## APIs Used
| API | Purpose | Auth |
|-----|---------|------|
| SponsorBlock API | Fetch sponsor segments for videos | None (public) |
| YouTube Web | Loaded in WebView | User's own Google login via WebView |

## SponsorBlock API
- **Endpoint**: `https://sponsor.ajay.app/api/skipSegments`
- **Params**: `videoID` (YouTube video ID)
- **Response**: JSON array of `{ segment: [start, end], category, UUID }`
- **Categories**: sponsor, selfpromo, interaction, intro, outro, preview, music_offtopic

## Security
- No API keys stored in the app
- User's Google credentials handled entirely by YouTube's web login in WebView
- WebView `allowFileAccess = false` (no local file access from web content)
- WebView `domStorageEnabled = true` (needed for YouTube)
- Userscripts are local files only — no remote script loading

## Background Playback
- Uses `ForegroundService` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
- MediaSession for lock screen / notification controls
- JS injected to prevent YouTube from pausing on `visibilitychange` events

## Error Handling
- Network errors shown via WebView error page
- SponsorBlock API failures silently ignored (non-critical)
- Userscript errors caught in try/catch wrappers

## Permissions Required
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```
