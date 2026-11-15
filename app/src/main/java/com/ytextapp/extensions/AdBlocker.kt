package com.ytextapp.extensions

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {

    private val AD_DOMAINS = setOf(
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

    private val AD_URL_PATTERNS = listOf(
        "/pagead/",
        "/get_video_info?",
        "/api/stats/ads",
        "googleads",
        "/ptracking",
        "doubleclick.net",
        "/ad_data_204",
        "innovid.com",
        "youtube.com/api/stats/qoe?adformat",
    )

    fun shouldBlockRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val host = request.url.host ?: return null

        if (AD_DOMAINS.any { host.contains(it, ignoreCase = true) }) {
            return createEmptyResponse()
        }

        if (AD_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }) {
            return createEmptyResponse()
        }

        return null
    }

    private fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream("".toByteArray())
        )
    }
}
