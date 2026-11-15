package com.ytextapp.extensions

import android.webkit.WebView
import com.ytextapp.webview.JsInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SponsorBlockInjector(
    private val jsInjector: JsInjector,
    private val client: SponsorBlockClient = SponsorBlockClient(),
) {
    private var lastVideoId: String? = null

    fun onPageLoaded(webView: WebView, url: String, scope: CoroutineScope) {
        val videoId = SponsorBlockClient.extractVideoId(url) ?: return

        // Don't re-fetch if same video
        if (videoId == lastVideoId) return
        lastVideoId = videoId

        scope.launch(Dispatchers.Main) {
            val segments = client.getSegments(videoId)
            if (segments.isEmpty()) return@launch

            val segmentsJson = segments.joinToString(",") { segment ->
                """{"start":${segment.start},"end":${segment.end},"category":"${segment.category}"}"""
            }

            val script = """
                if (window._ytExtSponsorBlock) {
                    window._ytExtSponsorBlock.setSegments([$segmentsJson]);
                }
            """.trimIndent()

            jsInjector.injectSingle(webView, script)
        }
    }

    fun reset() {
        lastVideoId = null
    }
}
