package com.ytextapp.webview

import android.content.Context
import android.webkit.WebView

/**
 * Builds the page-side userscript pipeline.
 *
 * Two changes keep injection off the UI thread's critical path:
 *  - asset files are read once and cached instead of re-read on every navigation;
 *  - all scripts are sent as one `evaluateJavascript` call instead of one call per
 *    script, which avoids a burst of IPC round-trips and keeps the scripts atomic.
 */
class JsInjector(private val context: Context) {

    private val scripts = mutableListOf<String>()
    private val assetCache = HashMap<String, String>()

    /** Config object read by every userscript; set before [injectAll]. */
    private var configScript: String? = null

    fun addScript(script: String) {
        scripts.add(script)
    }

    fun clearScripts() {
        scripts.clear()
    }

    fun loadAssetScript(assetPath: String): String =
        assetCache.getOrPut(assetPath) {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }

    /**
     * Publishes the current extension flags to the page. Kept as a small separate
     * script so a toggle can take effect immediately, without a reload and without
     * re-running the whole userscript pipeline.
     */
    fun setConfig(adBlockEnabled: Boolean, sponsorBlockEnabled: Boolean, backgroundPlayEnabled: Boolean) {
        configScript = buildString {
            append("window.__ytExtConfig={")
            append("adBlock:").append(adBlockEnabled).append(',')
            append("sponsorBlock:").append(sponsorBlockEnabled).append(',')
            append("backgroundPlay:").append(backgroundPlayEnabled)
            append("};")
        }
    }

    fun injectAll(webView: WebView) {
        if (scripts.isEmpty()) return

        val combined = buildString {
            configScript?.let { append(it).append('\n') }

            // One try/catch per script: a broken userscript must not take the
            // other extensions down with it.
            scripts.forEach { script ->
                append("(function(){try{\n")
                append(script)
                append("\n}catch(e){console.error('YTExt injection error:',e);}})();\n")
            }

            append("window.dispatchEvent(new Event('yt-ext-config'));")
        }

        webView.evaluateJavascript(combined, null)
    }

    /** Re-applies the current config to an already-loaded document. */
    fun updateConfig(webView: WebView) {
        val config = configScript ?: return
        webView.evaluateJavascript(
            "$config\nwindow.dispatchEvent(new Event('yt-ext-config'));",
            null,
        )
    }

    fun injectSingle(webView: WebView, script: String) {
        val wrappedScript = """
            (function() {
                try {
                    $script
                } catch(e) {
                    console.error('YTExt injection error:', e);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(wrappedScript, null)
    }
}
