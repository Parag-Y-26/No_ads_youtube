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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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

/** Userscripts are always injected; they read `window.__ytExtConfig` to decide what to do. */
private val SCRIPT_ASSETS = listOf(
    "js/adblock.js",
    "js/sponsorblock.js",
    "js/background_playback.js",
    "js/download_button.js",
)

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
    onNavigationStateChanged: (WebViewNavigationState) -> Unit = {},
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
    val navStateCallback = rememberUpdatedState(onNavigationStateChanged)

    // CanGoBack/CanGoForward are not observable; the callback below publishes them
    // into Compose state so the system back gesture and the in-app buttons agree.
    var canGoBack by remember { mutableStateOf(false) }

    // YouTube's fullscreen player needs to be closed before WebView history when the
    // user presses back, otherwise the navigation happens behind the fullscreen view.
    var fullscreenCallback by remember {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

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
                    currentVideoId = null
                    sponsorBlockJob?.cancel()
                    sponsorBlockJob = null

                    view?.let { publishNavigationState(it) }
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    view ?: return

                    injectScripts(view)
                    handleNavigation(view, pageUrl)
                    publishNavigationState(view)
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
                    if (scriptsInjected) {
                        // Config is cheap to push and keeps rapid toggle changes in sync.
                        jsInjector.updateConfig(view)
                        notifyScriptsOfSpaNavigation(view)
                        handleNavigation(view, url)
                    }

                    publishNavigationState(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // Stay in the WebView for every URL, including YouTube links.
                    return false
                }

                /** Builds and pushes the userscript pipeline for the current document. */
                private fun injectScripts(view: WebView) {
                    jsInjector.clearScripts()

                    // Every script is injected regardless of the toggles. The config
                    // object is the single source of truth, so flipping a switch takes
                    // effect instantly instead of waiting for the next full page load.
                    for (assetPath in SCRIPT_ASSETS) {
                        try {
                            jsInjector.addScript(jsInjector.loadAssetScript(assetPath))
                        } catch (_: Exception) {
                        }
                    }

                    for (script in userscriptManager.getScripts()) {
                        jsInjector.addScript(script)
                    }

                    jsInjector.setConfig(
                        adBlockEnabled = adBlockState.value,
                        sponsorBlockEnabled = sponsorBlockState.value,
                        backgroundPlayEnabled = backgroundPlayState.value,
                    )
                    jsInjector.injectAll(view)
                    scriptsInjected = true
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

                private fun publishNavigationState(view: WebView) {
                    val canBack = view.canGoBack()
                    canGoBack = canBack
                    navStateCallback.value(
                        WebViewNavigationState(
                            canGoBack = canBack,
                            canGoForward = view.canGoForward(),
                            url = view.url,
                            title = view.title,
                        )
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    fullscreenCallback = callback
                    onFullscreenChanged(view)
                }

                override fun onHideCustomView() {
                    fullscreenCallback = null
                    onFullscreenChanged(null)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    view?.let {
                        navStateCallback.value(
                            WebViewNavigationState(
                                canGoBack = it.canGoBack(),
                                canGoForward = it.canGoForward(),
                                url = it.url,
                                title = title ?: it.title,
                            )
                        )
                    }
                }
            }

            loadUrl(url)
        }
    }

    // Lets the foreground service drive this WebView from media buttons and the
    // MediaStyle notification.
    DisposableEffect(webView) {
        BackgroundPlaybackService.attachWebView(webView)
        onDispose {
            BackgroundPlaybackService.detachWebView(webView)
            if (webViewState.value === webView) webViewState.value = null
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    // Publish the instance to the parent after composition instead of writing state
    // during composition.
    SideEffect {
        webViewState.value = webView
    }

    // Toggles are applied to the live document; no reload required.
    LaunchedEffect(adBlockEnabled, sponsorBlockEnabled, backgroundPlayEnabled) {
        jsInjector.setConfig(adBlockEnabled, sponsorBlockEnabled, backgroundPlayEnabled)
        jsInjector.updateConfig(webView)
    }

    // Turning SponsorBlock on while a video is already open must fetch the segments
    // for that video; the normal fetch only runs on a page transition.
    LaunchedEffect(sponsorBlockEnabled) {
        if (!sponsorBlockEnabled) return@LaunchedEffect
        val videoId = webView.url?.let { SponsorBlockClient.extractVideoId(it) }
            ?: return@LaunchedEffect
        sponsorBlockInjector.loadSegments(webView, videoId, coroutineScope)
    }

    // The user agent only applies to the next document, so apply the change and
    // navigate once (not on the initial composition).
    val appliedDesktopMode = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(desktopMode) {
        val previous = appliedDesktopMode.value
        appliedDesktopMode.value = desktopMode
        if (previous != null && previous != desktopMode) {
            WebViewConfig.setDesktopMode(webView, desktopMode)
            val targetUrl = WebViewConfig.youtubeUrlForMode(webView.url, desktopMode)
            if (targetUrl == webView.url) webView.reload() else webView.loadUrl(targetUrl)
        }
    }

    // Handle back press — close the fullscreen player first, otherwise go back in
    // web history. Reading canGoBack() directly in the enabled parameter would
    // freeze the state at the first composition.
    BackHandler(enabled = canGoBack || fullscreenCallback != null) {
        val callback = fullscreenCallback
        if (callback != null) {
            fullscreenCallback = null
            callback.onCustomViewHidden()
            onFullscreenChanged(null)
        } else {
            webView.goBack()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}
