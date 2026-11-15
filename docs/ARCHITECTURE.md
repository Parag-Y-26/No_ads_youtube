# ARCHITECTURE.md — YouTube Ext App

## Tech Stack
| Layer | Technology |
|-------|------------|
| Language | Kotlin |
| UI | Jetpack Compose + AndroidView (for WebView) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| Build | Gradle (Kotlin DSL) |
| DI | Manual (simple app, no need for Hilt) |
| Networking | OkHttp (for SponsorBlock API) |
| Storage | SharedPreferences / DataStore |
| Background | Foreground Service + MediaSession |

## Folder Structure
```
app/src/main/
├── java/com/ytextapp/
│   ├── MainActivity.kt              # Single Activity entry point
│   ├── ui/
│   │   ├── MainScreen.kt            # Main Compose screen
│   │   ├── SettingsSheet.kt         # Bottom sheet settings
│   │   ├── DownloadBottomSheet.kt   # Video download options sheet
│   │   └── theme/
│   │       └── Theme.kt             # Material 3 theme
│   ├── download/
│   │   ├── DownloadBridge.kt        # JS interface for video download requests
│   │   ├── DownloadConfig.kt        # Container format & quality settings
│   │   ├── MediaStoreHelper.kt      # Scoped storage video persistence
│   │   └── VideoDownloadManager.kt  # Download queue and notifications
│   ├── webview/
│   │   ├── YouTubeWebView.kt        # WebView composable wrapper
│   │   ├── WebViewConfig.kt         # WebView setup & settings
│   │   └── JsInjector.kt            # JavaScript injection pipeline
│   ├── extensions/
│   │   ├── AdBlocker.kt             # Ad blocking JS + WebViewClient filter
│   │   ├── SponsorBlockClient.kt    # SponsorBlock API calls
│   │   ├── SponsorBlockInjector.kt  # Inject SponsorBlock overlay + skip logic
│   │   └── UserscriptManager.kt     # Load/manage custom userscripts
│   ├── service/
│   │   └── BackgroundPlaybackService.kt  # Foreground service for audio
│   └── data/
│       └── PreferencesManager.kt    # App settings persistence
├── assets/
│   └── js/
│       ├── adblock.js               # Ad blocking script
│       ├── background_playback.js   # Background playback keepalive
│       ├── download_button.js       # In-page download button injector
│       └── sponsorblock.js          # SponsorBlock script
└── res/
    └── ...
```

## Data Flow
1. **App starts** → `MainActivity` → `MainScreen` composable
2. **WebView loads YouTube** → `WebViewConfig` sets user agent, JS enabled
3. **Page loaded** → `JsInjector` runs injection pipeline:
   - Inject `adblock.js` (if enabled)
   - Inject `sponsorblock.js` (if enabled)
   - Inject any user scripts
4. **User minimizes app** → `BackgroundPlaybackService` starts as foreground service, keeps audio alive
5. **SponsorBlock flow**: video ID extracted → API queried for segments → JS injected to auto-skip
6. **Settings** saved to DataStore, read on startup

## Key Design Decisions
- **WebView over custom player**: We load the full YouTube site for maximum compatibility with existing web-based extensions/scripts
- **JS injection over WebViewClient**: More flexible; scripts can be updated without app updates
- **Foreground service for background play**: Required by Android for background audio; uses MediaSession for notification controls
