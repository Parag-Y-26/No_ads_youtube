# TASKS.md — YouTube Ext App

## Phase 1: Project Setup
- [x] T1: Scaffold Android project with Gradle Kotlin DSL
- [x] T2: Set up Material 3 theme + dark/light mode
- [x] T3: Create MainActivity + MainScreen composable

## Phase 2: Core WebView
- [x] T4: Build YouTubeWebView composable with proper config
- [x] T5: Implement JsInjector pipeline
- [x] T6: Add toolbar with URL bar, back/forward, reload
- [x] T7: Desktop/Mobile user agent toggle

## Phase 3: Extensions
- [x] T8: Ad blocking — JS injection + URL-level blocking in WebViewClient
- [x] T9: SponsorBlock API client
- [x] T10: SponsorBlock JS injection (auto-skip segments)
- [x] T11: Userscript manager — load/inject custom JS from local storage

## Phase 4: Background Playback
- [x] T12: Foreground service for background audio
- [x] T13: JS injection to prevent YouTube pause on visibility change
- [x] T14: MediaSession integration (lock screen controls)

## Phase 5: Settings & Polish
- [x] T15: Settings bottom sheet (toggle features)
- [x] T16: Preferences persistence with DataStore
- [x] T17: App icon and splash
- [ ] T18: Build signed APK
- [x] T19: Initialize git repository, add .gitignore/README, and push to GitHub (Parag-Y-26/No_ads_youtube)
- [x] T20: Configure Gradle for Android Studio 2026 / JDK 25 and build debug APK
