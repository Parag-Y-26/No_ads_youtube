package com.ytextapp.extensions

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Host/path based request filter.
 *
 * Every request the WebView makes runs through [shouldBlockRequest] on a Chromium
 * background thread, so the lookup has to stay cheap. The previous version scanned a
 * domain list with `String.contains` per request (16 host scans plus 9 URL scans, each
 * allocating); hosts are now matched with hash lookups against immutable sets.
 *
 * The collections are built once and never mutated, which is what makes them safe to
 * read concurrently from the several request threads Chromium uses.
 */
object AdBlocker {

    /** Hosts blocked verbatim. */
    private val AD_HOSTS: Set<String> = hashSetOf(
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "ad.doubleclick.net",
        "ads.youtube.com",
        "www.googleadservices.com",
        "tpc.googlesyndication.com",
        "ade.googlesyndication.com",
        "static.doubleclick.net",
        "s0.2mdn.net",
        "s1.2mdn.net",
        "googleadservices.com",
        "pagead.l.doubleclick.net",
        "video-ad-stats.googlesyndication.com",
        "redirector.googlevideo.com",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
    )

    /**
     * Hosts that are blocked together with every subdomain, which is what the old
     * `contains` check approximated.
     *
     * `googlevideo.com` is deliberately absent: only the ad redirector host above is
     * blocked, because the actual video CDN lives under the same domain and blocking
     * the suffix would kill playback.
     */
    private val AD_HOST_SUFFIXES: Set<String> = hashSetOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "2mdn.net",
        "innovid.com",
    )

    /** Path/query fragments, pre-lowered once so each request lowercases only itself. */
    private val AD_URL_FRAGMENTS: List<String> = listOf(
        "/pagead/",
        "/get_video_info?",
        "/api/stats/ads",
        "/ptracking",
        "/ad_data_204",
        "youtube.com/api/stats/qoe?adformat",
    )

    fun shouldBlockRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host ?: return null

        if (isBlockedHost(host)) return createEmptyResponse()

        // Only paths need substring matching, so the URL is lowered exactly once.
        val loweredUrl = url.toString().lowercase(Locale.ROOT)
        if (AD_URL_FRAGMENTS.any { loweredUrl.contains(it) }) return createEmptyResponse()

        return null
    }

    /**
     * Exact match, then walk up the labels: `ads.doubleclick.net` -> `doubleclick.net`.
     * That is one hash lookup per label instead of a scan over every configured domain,
     * and it reproduces the old subdomain behaviour without a regex per request.
     */
    private fun isBlockedHost(host: String): Boolean {
        var candidate = host.lowercase(Locale.ROOT)
        while (true) {
            if (candidate in AD_HOSTS || candidate in AD_HOST_SUFFIXES) return true

            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * A fresh response per call is what makes this thread-safe: a
     * [WebResourceResponse] owns its input stream, so handing the same instance to two
     * requests would leave the second one reading an already-consumed stream.
     */
    private fun createEmptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        204,
        "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
