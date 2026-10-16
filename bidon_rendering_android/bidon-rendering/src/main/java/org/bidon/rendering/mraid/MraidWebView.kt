package org.bidon.rendering.mraid

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebChromeClient
import org.bidon.rendering.html.ClickHandler
import org.bidon.rendering.html.HtmlWebView

/**
 * Extends HtmlWebView with MRAID support:
 *  - Injects mraid.js before creative loads
 *  - Attaches MraidJsBridge
 *  - Exposes JS-firing helpers for the controller
 */
internal class MraidWebView(context: Context) : HtmlWebView(context) {

    private var bridge: MraidJsBridge? = null

    /**
     * Called as the user touches the ad, or presses a key that activates what has focus in it, before the creative
     * sees either.
     */
    var onUserInput: (() -> Unit)? = null

    // No settings are changed here on purpose. HtmlWebView owns the WebView configuration for
    // both HTML and MRAID; this class used to override mixedContentMode with a different value
    // than its own base class, which meant the two disagreed and neither had been chosen.

    // ──────────────────────────────────────────
    // Setup
    // ──────────────────────────────────────────

    fun attachBridge(
        bridge: MraidJsBridge,
        onClick: () -> Unit,
        onPageLoaded: () -> Unit,
        onPageLoadError: (errorCode: Int, description: String) -> Unit,
        onWebViewGone: (didCrash: Boolean) -> Unit
    ) {
        this.bridge = bridge

        addJavascriptInterface(bridge, MraidJsBridge.BRIDGE_NAME)

        webViewClient = ClickHandler(
            context = context,
            onClick = onClick,
            onPageLoaded = onPageLoaded,
            onPageLoadError = onPageLoadError,
            onWebViewGone = onWebViewGone
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

    /**
     * The state change to `default`, then `ready`. Delivered at once, not queued until the view is in a window as the
     * other events are: many creatives draw only on `ready`, and a creative that has drawn nothing by the end of its
     * load is turned away as blank. Main thread.
     */
    fun fireReadyEvent() {
        evaluateJavascript("mraid.fireStateChangeEvent('${MraidState.DEFAULT.toJs()}'); mraid.fireReadyEvent()", null)
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

    /** [message] and [action] are the SDK's own constants, never text from the creative. */
    fun fireError(message: String, action: String) {
        evaluateSafe("mraid.fireErrorEvent('$message', '$action')")
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserInput?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode in ACTIVATION_KEYS) {
            onUserInput?.invoke()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun destroy() {
        bridge = null
        super.destroy()
    }

    private companion object {
        /** The keys Android and the web take as activating what has focus. */
        val ACTIVATION_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
        )
    }
}
