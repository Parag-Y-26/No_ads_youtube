package com.ytextapp.download

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ytextapp.data.PreferencesManager
import com.ytextapp.ui.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.util.regex.Pattern

/**
 * JavaScript bridge that receives download requests from the injected page script.
 *
 * The bridge is added to the WebView as `window.YTExtDownloader` and validates the
 * incoming URL before forwarding the request to the Compose layer. All validation
 * runs on a background thread; the callback to the UI is posted to the main thread
 * via a [Handler] tied to the main looper.
 *
 * Thread-safety: the WebView may call this from any thread (usually the Chromium
 * network thread), so we never touch UI state directly.
 */
class DownloadBridge(
    private val context: android.content.Context,
    private val preferencesManager: PreferencesManager,
    private val coroutineScope: CoroutineScope,
    private val onDownloadRequested: (DownloadRequest) -> Unit,
    private val onProgressRequested: () -> Unit,
) : DefaultLifecycleObserver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val WATCH_URL_PATTERN = Pattern.compile(
        """^https?://(?:m\.)?youtube\.com/watch\?v=[a-zA-Z0-9_-]{11}""")
    private val SHORTS_URL_PATTERN = Pattern.compile(
        """^https?://(?:m\.)?youtube\.com/shorts/[a-zA-Z0-9_-]{11}""")
    private val EMBED_URL_PATTERN = Pattern.compile(
        """^https?://(?:m\.)?youtube\.com/embed/[a-zA-Z0-9_-]{11}""")
    private val SHORT_URL_PATTERN = Pattern.compile(
        """^https?://youtu\.be/[a-zA-Z0-9_-]{11}""")

    /**
     * Called from JavaScript via `window.YTExtDownloader.requestDownload(...)`.
     *
     * @param url              The page URL (watch/shorts/embed).
     * @param title            The document title (used as file name).
     * @param directStreamUrl  Optional direct `<video src="">` when it is a plain URL
     *                         (not a blob: or MediaSource stream). If absent or invalid,
     *                         the download sheet will fall back to the external-downloader
     *                         path.
     */
    @JavascriptInterface
    fun requestDownload(
        url: String?,
        title: String?,
        directStreamUrl: String?
    ) {
        Log.d("YTExtDownloader", "requestDownload called: url=$url, title=$title, directStreamUrl=$directStreamUrl")
        // Offload validation to IO so the WebView thread isn't blocked.
        coroutineScope.launch(Dispatchers.IO) {
            val validated = validateAndBuildRequest(url, title, directStreamUrl)
            if (validated != null) {
                Log.d("YTExtDownloader", "Validated request: $validated")
                mainHandler.post { onDownloadRequested(validated) }
            } else {
                Log.w("YTExtDownloader", "Validation failed for url=$url, title=$title")
            }
        }
    }

    /**
     * Called from JavaScript via `window.YTExtDownloader.showProgress()` when user
     * long-presses the download button.
     */
    @JavascriptInterface
    fun showProgress() {
        Log.d("YTExtDownloader", "showProgress called, posting onProgressRequested")
        mainHandler.post { 
            Log.d("YTExtDownloader", "about to call onProgressRequested")
            onProgressRequested() 
        }
    }

    private fun validateAndBuildRequest(
        url: String?,
        title: String?,
        directStreamUrl: String?
    ): DownloadRequest? {
        val cleanUrl = url?.trim() ?: return null
        val cleanTitle = title?.trim() ?: return null

        // Accept watch, shorts, embed, and youtu.be URLs.
        val isValidUrl = WATCH_URL_PATTERN.matcher(cleanUrl).matches()
            || SHORTS_URL_PATTERN.matcher(cleanUrl).matches()
            || EMBED_URL_PATTERN.matcher(cleanUrl).matches()
            || SHORT_URL_PATTERN.matcher(cleanUrl).matches()

        if (!isValidUrl) return null

        // Extract video ID for the DownloadRequest.
        val videoId = extractVideoId(cleanUrl) ?: return null

        // Validate optional direct stream URL (must be http/https, not blob:).
        var validatedStreamUrl: String? = null
        directStreamUrl?.trim()?.let { stream ->
            if (stream.startsWith("http://") || stream.startsWith("https://")) {
                try {
                    URL(stream).toURI() // throws on malformed
                    validatedStreamUrl = stream
                } catch (_: Exception) {
                    // Ignore, will use external downloader path
                }
            }
        }

        // Read current format/quality defaults from preferences.
        // Use runBlocking on IO to get the current value from the Flow.
        val formatStr = runBlocking { preferencesManager.defaultDownloadFormat.first() }
        val qualityStr = runBlocking { preferencesManager.defaultDownloadQuality.first() }

        val format = ContainerFormat.fromExtension(formatStr) ?: ContainerFormat.MP4

        return DownloadRequest(
            videoId = videoId,
            url = cleanUrl,
            title = cleanTitle,
            format = format,
            quality = qualityStr,
        )
    }

    private fun extractVideoId(url: String): String? {
        // watch?v=ID
        val watchMatch = """[?&]v=([a-zA-Z0-9_-]{11})""".toRegex().find(url)
        watchMatch?.groupValues?.get(1)?.let { return it }

        // shorts/ID
        val shortsMatch = """shorts/([a-zA-Z0-9_-]{11})""".toRegex().find(url)
        shortsMatch?.groupValues?.get(1)?.let { return it }

        // embed/ID
        val embedMatch = """embed/([a-zA-Z0-9_-]{11})""".toRegex().find(url)
        embedMatch?.groupValues?.get(1)?.let { return it }

        // youtu.be/ID
        val shortMatch = """youtu\.be/([a-zA-Z0-9_-]{11})""".toRegex().find(url)
        shortMatch?.groupValues?.get(1)?.let { return it }

        return null
    }

    /**
     * Installs the bridge into the WebView under the name "YTExtDownloader".
     */
    fun attachTo(webView: WebView) {
        webView.addJavascriptInterface(this, "YTExtDownloader")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // No-op: the bridge lives as long as the WebView, which is the activity scope.
    }
}