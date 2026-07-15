package org.bidon.rendering.mraid

import android.content.Context
import android.webkit.WebChromeClient
import org.bidon.rendering.html.ClickHandler
import org.bidon.rendering.html.HtmlWebView

/**
 * Extends HtmlWebView with MRAID support:
 *  - Injects mraid.js before creative loads
 *  - Attaches MraidJsBridge
 *  - Exposes JS-firing helpers for the controller
 */
class MraidWebView(context: Context) : HtmlWebView(context) {

    private var bridge: MraidJsBridge? = null

    init {
        settings.mixedContentMode =
            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    // ──────────────────────────────────────────
    // Setup
    // ──────────────────────────────────────────

    fun attachBridge(
        bridge: MraidJsBridge,
        onClick: () -> Unit,
        onPageLoaded: () -> Unit,
        onPageLoadError: (String) -> Unit
    ) {
        this.bridge = bridge

        addJavascriptInterface(bridge, MraidJsBridge.BRIDGE_NAME)

        webViewClient = ClickHandler(
            context = context,
            onClick = onClick,
            onPageLoaded = onPageLoaded,
            onPageLoadError = onPageLoadError
        )

        webChromeClient = WebChromeClient()
    }

    /**
     * Load MRAID creative markup.
     * mraid.js is injected as a <script> tag before the creative HTML
     * so it is available synchronously when the creative's own scripts run.
     */
    fun loadMraidContent(html: String, mraidJs: String) {
        val injected = injectMraidScript(html, mraidJs)
        loadDataWithBaseURL(null, injected, "text/html", "UTF-8", null)
    }

    // ──────────────────────────────────────────
    // JS event helpers (called by MraidController)
    // ──────────────────────────────────────────

    fun fireReadyEvent() {
        evaluateSafe("mraid.fireReadyEvent()")
    }

    fun fireStateChange(state: MraidState) {
        evaluateSafe("mraid.fireStateChangeEvent('${state.toJs()}')")
    }

    fun fireViewableChange(isViewable: Boolean) {
        evaluateSafe("mraid.fireViewableChangeEvent($isViewable)")
    }

    fun fireSizeChange(width: Int, height: Int) {
        evaluateSafe("mraid.fireSizeChangeEvent($width, $height)")
    }

    // ──────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────

    private fun evaluateSafe(js: String) {
        post { evaluateJavascript(js, null) }
    }

    /**
     * Injects mraid.js before </head> or before creative HTML if no head tag present.
     * The script tag runs synchronously so mraid is ready before creative scripts.
     */
    private fun injectMraidScript(html: String, mraidJs: String): String {
        val scriptTag = "<script>\n$mraidJs\n</script>"

        return when {
            html.contains("</head>", ignoreCase = true) ->
                html.replace("</head>", "$scriptTag</head>", ignoreCase = true)

            html.contains("<head>", ignoreCase = true) ->
                html.replace("<head>", "<head>$scriptTag", ignoreCase = true)

            html.contains("<html>", ignoreCase = true) ->
                html.replace("<html>", "<html><head>$scriptTag</head>", ignoreCase = true)

            else -> "$scriptTag\n$html"
        }
    }

    override fun destroy() {
        bridge = null
        super.destroy()
    }
}