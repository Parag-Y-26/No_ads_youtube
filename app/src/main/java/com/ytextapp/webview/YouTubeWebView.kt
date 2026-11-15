package com.ytextapp.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ytextapp.data.PreferencesManager
import com.ytextapp.download.DownloadBridge
import com.ytextapp.download.DownloadRequest
import com.ytextapp.extensions.AdBlocker
import com.ytextapp.extensions.SponsorBlockClient
import com.ytextapp.extensions.SponsorBlockInjector
import com.ytextapp.extensions.UserscriptManager
import com.ytextapp.service.BackgroundPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * A WebView that keeps reporting itself visible while background playback is enabled.
 *
 * [WebView.onWindowVisibilityChanged] is the callback Chromium's `AwContents` uses to learn
 * whether its window is on screen; once it hears `GONE` it pauses media and throttles
 * timers. The activity going to the background, or the screen turning off, would otherwise
 * stop playback even though the foreground service is still alive.
 *
 * The flag arrives as a lambda so the decision always reads the *current* preference
 * instead of the value captured when the instance was built.
 */
private class BackgroundPlaybackWebView(
    context: Context,
    private val isBackgroundPlaybackEnabled: () -> Boolean,
) : WebView(context) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        val effectiveVisibility =
            if (isBackgroundPlaybackEnabled()) View.VISIBLE else visibility
        super.onWindowVisibilityChanged(effectiveVisibility)
    }
}

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
    onDownloadRequested: (DownloadRequest) -> Unit,
    onProgressRequested: () -> Unit,
) {
    val context = LocalContext.current
    val jsInjector = remember { JsInjector(context) }
    val sponsorBlockInjector = remember { SponsorBlockInjector(jsInjector) }
    val userscriptManager = remember { UserscriptManager(context) }
    val preferencesManager = remember { PreferencesManager(context) }

    // The WebViewClient is created once and outlives recomposition. Reading the
    // parameters directly inside it would freeze them at their first-composition
    // values, so the toggles would stop working. These holders always resolve to
    // the value from the latest composition.
    val adBlockState = rememberUpdatedState(adBlockEnabled)
    val sponsorBlockState = rememberUpdatedState(sponsorBlockEnabled)
    val backgroundPlayState = rememberUpdatedState(backgroundPlayEnabled)

    // Download bridge — created once, lives as long as the WebView.
    val downloadBridge = remember {
        DownloadBridge(context, preferencesManager, coroutineScope, onDownloadRequested, onProgressRequested)
    }

    val webView = remember {
        BackgroundPlaybackWebView(context) { backgroundPlayState.value }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            WebViewConfig.configure(this, desktopMode)

            // Register the JS bridge
            downloadBridge.attachTo(this)

            webViewClient = object : WebViewClient() {

                /** Video id of the document on screen — from either watch?v= or shorts/. */
                private var currentVideoId: String? = null

                /** Fetch for the previous video; cancelled the instant the id changes. */
                private var sponsorBlockJob: Job? = null

                /** True once the userscripts have run in the current document. */
                private var scriptsInjected = false

                /** Feature flags as they were when the userscripts were last pushed. */
                private var injectedFlags: List<Boolean>? = null

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (adBlockState.value && request != null) {
                        val blocked = AdBlocker.shouldBlockRequest(request)
                        if (blocked != null) return blocked
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)

                    // A new document: every window-level flag set by the userscripts is
                    // gone, so the pipeline must run again even for the same video.
                    scriptsInjected = false
                    injectedFlags = null
                    currentVideoId = null
                    sponsorBlockJob?.cancel()
                    sponsorBlockJob = null
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    view ?: return

                    injectScripts(view)
                    handleNavigation(view, pageUrl)
                }

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean
                ) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view ?: return

                    // m.youtube.com navigates with history.pushState, which never fires
                    // onPageFinished — this is the only callback that sees the SPA
                    // transitions, so the video change has to be detected here.
                    if (!scriptsInjected) return // still a full load; onPageFinished handles it

                    // A toggle flipped after the document loaded cannot retroactively add
                    // its script, so rebuild the pipeline whenever the live flags differ
                    // from the ones it was built from. The scripts are idempotent, so
                    // re-running the ones already present is a no-op.
                    if (injectedFlags != currentFlags()) {
                        injectScripts(view)
                    }

                    notifyScriptsOfSpaNavigation(view)
                    handleNavigation(view, url)
                }

                /** Snapshot of the feature flags the userscript pipeline depends on. */
                private fun currentFlags(): List<Boolean> = listOf(
                    adBlockState.value,
                    sponsorBlockState.value,
                    backgroundPlayState.value,
                )

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

                /** Builds and pushes the userscript pipeline for the current document. */
                private fun injectScripts(view: WebView) {
                    jsInjector.clearScripts()

                    if (adBlockState.value) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript("js/adblock.js"))
                        } catch (_: Exception) {}
                    }

                    if (sponsorBlockState.value) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript("js/sponsorblock.js"))
                        } catch (_: Exception) {}
                    }

                    if (backgroundPlayState.value) {
                        try {
                            jsInjector.addScript(
                                jsInjector.loadAssetScript("js/background_playback.js")
                            )
                        } catch (_: Exception) {}
                    }

                    // Download button is always injected (no preference toggle needed).
                    try {
                        jsInjector.addScript(jsInjector.loadAssetScript("js/download_button.js"))
                    } catch (_: Exception) {}

                    for (script in userscriptManager.getScripts()) {
                        jsInjector.addScript(script)
                    }

                    jsInjector.injectAll(view)
                    scriptsInjected = true
                    injectedFlags = currentFlags()
                }

                /** Re-fetches SponsorBlock data, but only when the video actually changed. */
                private fun handleNavigation(view: WebView, pageUrl: String?) {
                    val targetUrl = pageUrl ?: return
                    val videoId = SponsorBlockClient.extractVideoId(targetUrl)

                    // Same video (or still off a video page): nothing to do. This is what
                    // stops SPA transitions like opening the comments from re-fetching.
                    if (videoId == currentVideoId) return
                    currentVideoId = videoId

                    // Whatever is still in flight belongs to the video we just left.
                    sponsorBlockJob?.cancel()
                    sponsorBlockJob = null

                    // Drop the old timestamps immediately; the fetch below is async and
                    // the player must not keep skipping while it runs.
                    sponsorBlockInjector.clearSegments(view)

                    if (videoId == null || !sponsorBlockState.value) return

                    sponsorBlockJob = sponsorBlockInjector.loadSegments(
                        webView = view,
                        videoId = videoId,
                        scope = coroutineScope,
                    )
                }

                /** Nudges the userscripts so they can reset their per-video state. */
                private fun notifyScriptsOfSpaNavigation(view: WebView) {
                    jsInjector.injectSingle(
                        view,
                        "window.dispatchEvent(new Event('state-navigateend'));",
                    )
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

    // Lets the foreground service drive this WebView from media buttons and the
    // MediaStyle notification.
    DisposableEffect(webView) {
        BackgroundPlaybackService.attachWebView(webView)
        onDispose { BackgroundPlaybackService.detachWebView(webView) }
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
