package com.ytextapp.webview

import android.webkit.WebSettings
import android.webkit.WebView

object WebViewConfig {

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.6613.127 Safari/537.36"

    fun configure(webView: WebView, desktopMode: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
        }
    }

    fun setDesktopMode(webView: WebView, enabled: Boolean) {
        webView.settings.userAgentString =
            if (enabled) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
    }
}
