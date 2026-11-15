package com.ytextapp.webview

import android.content.Context
import android.webkit.WebView

class JsInjector(private val context: Context) {

    private val scripts = mutableListOf<String>()

    fun addScript(script: String) {
        scripts.add(script)
    }

    fun clearScripts() {
        scripts.clear()
    }

    fun loadAssetScript(assetPath: String): String {
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    fun injectAll(webView: WebView) {
        for (script in scripts) {
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
