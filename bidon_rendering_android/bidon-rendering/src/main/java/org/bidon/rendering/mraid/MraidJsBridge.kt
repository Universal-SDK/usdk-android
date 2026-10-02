package org.bidon.rendering.mraid

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Native Kotlin class with @JavascriptInterface methods.
 * mraid.js calls these from the WebView thread;
 * we dispatch to main thread before touching any UI via MraidController.
 */
internal class MraidJsBridge(
    private val controller: MraidController
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    @JavascriptInterface
    fun open(url: String) {
        onMain { controller.open(url) }
    }

    @JavascriptInterface
    fun expand(url: String?) {
        onMain { controller.expand(url) }
    }

    @JavascriptInterface
    fun expand() {
        onMain { controller.expand(null) }
    }

    @JavascriptInterface
    fun close() {
        onMain { controller.close() }
    }

    /**
     * @param width     desired width in density-independent pixels
     * @param height    desired height in density-independent pixels
     * @param offsetX   x offset from current position (dp)
     * @param offsetY   y offset from current position (dp)
     */
    @JavascriptInterface
    fun resize(width: Int, height: Int, offsetX: Int, offsetY: Int) {
        onMain { controller.resize(width, height, offsetX, offsetY) }
    }

    @JavascriptInterface
    fun playVideo(url: String) {
        onMain { controller.playVideo(url) }
    }

    @JavascriptInterface
    fun storePicture(url: String) {
        onMain { controller.storePicture(url) }
    }

    @JavascriptInterface
    fun createCalendarEvent(description: String, start: String) {
        onMain { controller.createCalendarEvent(description, start) }
    }

    @JavascriptInterface
    fun getState(): String = controller.currentState.toJs()

    companion object {
        const val BRIDGE_NAME = "_mraidBridge"
    }
}