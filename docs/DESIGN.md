# DESIGN.md — YouTube Ext App

## Product Goal
An Android app that loads YouTube's web interface inside a WebView, enhanced with extension-like features (ad blocking, SponsorBlock, background playback) and the ability to inject custom userscripts.

## Target Users
- Android users who want an ad-free YouTube experience
- Power users who want extension-like features on mobile
- Users who want background audio playback without YouTube Premium

## Core Features
1. **YouTube WebView** — Load YouTube (mobile or desktop site) in a full-screen WebView
2. **Ad Blocking** — Inject JavaScript to block video ads and banner ads
3. **SponsorBlock** — Skip sponsor segments using the SponsorBlock API
4. **Background Playback** — Continue audio when app is minimized or screen is off
5. **Desktop/Mobile Toggle** — Switch between desktop and mobile YouTube user agents
6. **Userscript Manager** — Load/manage custom JS scripts to inject into pages

## UI/UX Style
- **Minimal chrome** — The WebView fills the screen; toolbar is a slim top bar
- **Bottom sheet settings** — Tap a gear icon for settings, toggle features
- **Material 3** with dynamic color theming
- **Colors:** YouTube red (#FF0000) accent, dark/light mode support
- **Fonts:** System default (Roboto)

## Key Components
| Component | Purpose |
|-----------|---------|
| `MainScreen` | Compose screen hosting the WebView + toolbar |
| `YouTubeWebView` | Custom WebView with JS injection pipeline |
| `AdBlocker` | JS injection module for ad blocking |
| `SponsorBlockClient` | API client for SponsorBlock segment data |
| `BackgroundPlaybackService` | Foreground service for audio when minimized |
| `UserscriptManager` | Load/save/inject custom JS files |
| `SettingsSheet` | Bottom sheet for toggles and preferences |
