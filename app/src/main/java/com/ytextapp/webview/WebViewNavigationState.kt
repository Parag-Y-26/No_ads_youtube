package com.ytextapp.webview

/**
 * Snapshot of the WebView history, so Compose can enable/disable the back and
 * forward controls without calling into the WebView on every recomposition.
 *
 * `WebView.canGoBack()` is not observable; without this the buttons would keep
 * whatever state they had when the composition first ran.
 */
data class WebViewNavigationState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val url: String? = null,
    val title: String? = null,
)
