package com.ytextapp.extensions

import android.webkit.WebView
import com.ytextapp.webview.JsInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fetches SponsorBlock segments for one video and hands them to the page.
 *
 * Deliberately stateless: the caller (the WebViewClient) owns the video lifecycle so
 * that a navigation can cancel the previous fetch instead of letting a slow response
 * land in the wrong player.
 */
class SponsorBlockInjector(
    private val jsInjector: JsInjector,
    private val client: SponsorBlockClient = SponsorBlockClient(),
) {

    /**
     * Fetches the segments for [videoId] and injects them.
     *
     * @return the [Job] driving the request, so the caller can cancel it as soon as the
     *   video changes. A cancelled job never reaches the injection step.
     */
    fun loadSegments(
        webView: WebView,
        videoId: String,
        scope: CoroutineScope,
    ): Job = scope.launch(Dispatchers.Main.immediate) {
        val segments = client.getSegments(videoId)

        // The user may already be on another video by the time the request completes.
        if (!isActive) return@launch

        val segmentsJson = segments.joinToString(",") { segment ->
            """{"start":${segment.start},"end":${segment.end},"category":"${segment.category}"}"""
        }

        // The video id travels with the payload so the page can reject it if the
        // SPA has moved on in the meantime.
        jsInjector.injectSingle(
            webView,
            """
            if (window._ytExtSponsorBlock) {
                window._ytExtSponsorBlock.setSegments([$segmentsJson], "$videoId");
            }
            """.trimIndent(),
        )
    }

    /**
     * Drops whatever segments the page currently holds. Called the moment the video
     * changes, because the fetch below is asynchronous and the player must not keep
     * skipping using the previous video's timestamps while it is in flight.
     */
    fun clearSegments(webView: WebView) {
        jsInjector.injectSingle(
            webView,
            """
            if (window._ytExtSponsorBlock) {
                window._ytExtSponsorBlock.setSegments([], null);
            }
            """.trimIndent(),
        )
    }
}
