package com.ytextapp.webview

import android.graphics.Color
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.net.toUri

object WebViewConfig {

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.6613.127 Safari/537.36"

    fun configure(webView: WebView, desktopMode: Boolean) {
        // A white WebView flashes before the page paints. Black matches the player
        // and makes the short gap between videos much less noticeable.
        webView.setBackgroundColor(Color.BLACK)

        // No blue glow / stretch effect while scrolling a video feed. It costs a
        // frame on every fling and is not something a native player does.
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isScrollbarFadingEnabled = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // Required for the ad blocker: media must be allowed to start without
            // an extra gesture after a skip or an SPA transition.
            mediaPlaybackRequiresUserGesture = false

            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // target="_blank" links stay in this WebView instead of being dropped.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true

            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
        }
    }

    /**
     * Changing the user agent only affects the next document, so the caller is
     * expected to reload the page afterwards.
     */
    fun setDesktopMode(webView: WebView, enabled: Boolean) {
        webView.settings.userAgentString =
            if (enabled) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
    }

    /**
     * Returns the URL to load after switching modes.
     *
     * Reloading alone is not enough. YouTube redirects desktop mode to
     * `www.youtube.com/?app=desktop`, and that query flag keeps forcing the desktop
     * layout even after the mobile user agent is restored. Swapping the host and
     * dropping the flag keeps the user on the same video instead of sending them home.
     */
    fun youtubeUrlForMode(currentUrl: String?, desktopMode: Boolean): String {
        val fallback = if (desktopMode) {
            "https://www.youtube.com/?app=desktop"
        } else {
            "https://m.youtube.com"
        }

        val current = currentUrl ?: return fallback
        if (!current.contains("youtube.com", ignoreCase = true)) return current

        return try {
            val uri = current.toUri()
            val builder = uri.buildUpon()
                .authority(if (desktopMode) "www.youtube.com" else "m.youtube.com")
                .clearQuery()

            uri.queryParameterNames.forEach { name ->
                if (name != "app") {
                    uri.getQueryParameter(name)?.let { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            }

            builder.build().toString()
        } catch (_: Exception) {
            fallback
        }
    }
}
