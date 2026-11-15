package com.ytextapp.webview

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ytextapp.extensions.AdBlocker
import com.ytextapp.extensions.SponsorBlockInjector
import com.ytextapp.extensions.UserscriptManager
import kotlinx.coroutines.CoroutineScope

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebView(
    modifier: Modifier = Modifier,
    url: String,
    desktopMode: Boolean,
    adBlockEnabled: Boolean,
    sponsorBlockEnabled: Boolean,
    backgroundPlayEnabled: Boolean,
    coroutineScope: CoroutineScope,
    webViewState: MutableState<WebView?>,
    onFullscreenChanged: (View?) -> Unit,
) {
    val context = LocalContext.current
    val jsInjector = remember { JsInjector(context) }
    val sponsorBlockInjector = remember { SponsorBlockInjector(jsInjector) }
    val userscriptManager = remember { UserscriptManager(context) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            WebViewConfig.configure(this, desktopMode)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (adBlockEnabled && request != null) {
                        val blocked = AdBlocker.shouldBlockRequest(request)
                        if (blocked != null) return blocked
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    view ?: return

                    // Build injection pipeline
                    jsInjector.clearScripts()

                    if (adBlockEnabled) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript("js/adblock.js"))
                        } catch (_: Exception) {}
                    }

                    if (sponsorBlockEnabled) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript("js/sponsorblock.js"))
                        } catch (_: Exception) {}
                    }

                    if (backgroundPlayEnabled) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript("js/background_playback.js"))
                        } catch (_: Exception) {}
                    }

                    // Add userscripts
                    for (script in userscriptManager.getScripts()) {
                        jsInjector.addScript(script)
                    }

                    jsInjector.injectAll(view)

                    // SponsorBlock segment fetching
                    if (sponsorBlockEnabled && pageUrl != null) {
                        sponsorBlockInjector.onPageLoaded(view, pageUrl, coroutineScope)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val loadUrl = request?.url?.toString() ?: return false
                    // Keep YouTube URLs in the WebView
                    if (loadUrl.contains("youtube.com") || loadUrl.contains("youtu.be") ||
                        loadUrl.contains("accounts.google.com")
                    ) {
                        return false
                    }
                    return false // Stay in WebView for all URLs
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    onFullscreenChanged(view)
                }

                override fun onHideCustomView() {
                    onFullscreenChanged(null)
                }
            }

            loadUrl(url)
        }
    }

    // Update the state so parent can access the WebView
    webViewState.value = webView

    // Handle desktop mode changes
    DisposableEffect(desktopMode) {
        WebViewConfig.setDesktopMode(webView, desktopMode)
        onDispose {}
    }

    // Handle back press — go back in web history
    BackHandler(enabled = webView.canGoBack()) {
        webView.goBack()
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}
