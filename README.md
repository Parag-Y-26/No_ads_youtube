# No_ads_Tube

An Android application built with Kotlin and Jetpack Compose that loads YouTube Web in an enhanced WebView with built-in extensions and userscript support.

## Features

- **Ad Blocking**: Network-level request interception + CSS/DOM MutationObserver JavaScript injection to block ads and auto-skip video ads.
- **SponsorBlock Integration**: Automatically queries the public SponsorBlock API for segments (sponsors, intros, self-promos) and skips them smoothly.
- **Background Playback**: Continues audio playback when minimizing the app or locking the screen via a foreground media service and Page Visibility API override.
- **Desktop / Mobile Toggle**: Switch user agents on the fly between desktop and mobile layouts.
- **Userscript Support**: Inject custom user `.js` scripts from the app's internal storage (`userscripts/`).
- **Material 3 UI**: Clean Compose toolbar, native fullscreen video playback, and a settings bottom sheet with DataStore persistence.

## Tech Stack

- **UI**: Jetpack Compose, Material 3
- **Language**: Kotlin (JVM 17)
- **Target SDK**: Android 35 (Min SDK 26)
- **Networking**: OkHttp 4.12
- **Persistence**: Jetpack DataStore Preferences
- **Media**: AndroidX MediaSession & Foreground Service

## Project Structure

```
├── app/
│   ├── src/main/
│   │   ├── assets/js/          # Injected JS scripts (adblock, sponsorblock, background_playback)
│   │   ├── java/com/ytextapp/  # Kotlin application source
│   │   │   ├── data/           # DataStore preferences
│   │   │   ├── extensions/     # AdBlocker, SponsorBlockClient, UserscriptManager
│   │   │   ├── service/        # BackgroundPlaybackService
│   │   │   ├── ui/             # Jetpack Compose screens, theme, and settings
│   │   │   └── webview/        # WebView config, client, and JS injector
│   │   └── res/                # Strings, colors, styles
│   └── build.gradle.kts
├── docs/                       # Project documentation (Design, Architecture, Rules, Tasks)
├── build.gradle.kts
└── settings.gradle.kts
```

## Getting Started

1. Clone or open this repository in **Android Studio** (Koala / Ladybug or newer recommended).
2. Allow Gradle to sync dependencies.
3. Connect an Android device (API 26+) or launch an emulator.
4. Click **Run** to build and install.
