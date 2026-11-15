# DESIGN.md — No_ads_Tube

## Product Goal
An Android app that loads YouTube's web interface inside a WebView, enhanced with extension-like features (ad blocking, SponsorBlock, background playback, video downloading) and custom branding.

## Target Users
- Android users who want an ad-free YouTube experience
- Power users who want extension-like features on mobile
- Users who want background audio playback without YouTube Premium
- Users who want to download videos directly to device storage

## Core Features
1. **YouTube WebView** — Load YouTube (mobile or desktop site) in a full-screen WebView
2. **Ad Blocking** — Inject JavaScript to block video ads and banner ads
3. **SponsorBlock** — Skip sponsor segments using the SponsorBlock API
4. **Background Playback** — Continue audio when app is minimized or screen is off
5. **Video Downloader** — In-app download sheet and toolbar download button with MP4/MKV and quality selection
6. **Desktop/Mobile Toggle** — Switch between desktop and mobile YouTube user agents
7. **Userscript Manager** — Load/manage custom JS scripts to inject into pages

## UI/UX Style
- **Minimal chrome** — The WebView fills the screen; toolbar is a slim top bar with navigation, download, refresh, and mode toggles
- **Bottom sheet dialogs** — Settings sheet and download configuration sheet
- **Material 3** with dynamic color theming
- **Branding:** Custom unlocked padlock with YouTube play icon, app name "No_ads_Tube"
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
| `VideoDownloadManager` | Handles downloading video streams to local storage |
| `DownloadBottomSheet` | UI for selecting format, quality, and download action |
| `UserscriptManager` | Load/save/inject custom JS files |
| `SettingsSheet` | Bottom sheet for toggles and preferences |
