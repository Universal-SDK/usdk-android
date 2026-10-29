package org.bidon.rendering.html

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import org.bidon.rendering.LinkOpener
import org.bidon.rendering.telemetry.ProcessExit

/**
 * Turns a tap on a creative into a click report and a click-through.
 *
 * The rule this class holds to: the ad's WebView shows the creative and nothing else. Every
 * navigation is either handled by opening it somewhere else, or refused. It must never be allowed
 * to load the destination itself, because then the ad silently becomes a web page with a close
 * button over it.
 */
internal class ClickHandler(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onPageLoaded: (() -> Unit)? = null,
    private val onPageLoadError: ((errorCode: Int, description: String) -> Unit)? = null,
    private val onWebViewGone: ((exit: ProcessExit) -> Unit)? = null
) : WebViewClient() {

    private companion object {
        const val TAG = "ClickHandler"
    }

    private val links = LinkOpener(context)

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val url = uri.toString()

        // The creative talking to itself, not a click.
        if (url.startsWith("javascript:") || url.startsWith("about:blank")) {
            return false
        }

        // A navigation no tap started is not a click: a browser would not hand it to another app either. The WebView
        // hands a tap to the first navigation after it and to no other, so one tap opens one link. Refused all the
        // same: the ad never becomes the page it was sent to.
        if (!request.hasGesture()) {
            Log.d(TAG, "Refused a navigation without a tap: $url")
            return true
        }

        Log.d(TAG, "Intercepted click: $url")

        onClick()

        links.open(uri)

        // Always true. Returning false hands the URL back to the WebView, which would replace
        // the creative with the advertiser's page inside the ad container.
        return true
    }

    /**
     * A form sent with POST replaces the page without asking [shouldOverrideUrlLoading], so the creative could turn the
     * ad into any page it likes. Its request is answered here, before it leaves the device, with "No Content", on which
     * the WebView keeps the page it has. A tapped form is turned away too: it is neither sent nor counted as a click.
     * Called on a background thread.
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request?.isForMainFrame != true || request.method != "POST") return null
        Log.d(TAG, "Refused a form sent from the creative: ${request.url}")
        return WebResourceResponse(null, null, 204, "No Content", emptyMap(), null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page loaded: $url")
        onPageLoaded?.invoke()
    }

    /**
     * This used to override the four-argument overload, which the framework stopped calling at
     * API 23. With minSdk 24 it could never fire, so a creative that failed to load reported
     * nothing at all and the only thing that noticed was the mediation SDK's fill timeout.
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)

        // Only the creative itself failing counts as a load failure. Creatives routinely pull in
        // tracking pixels and assets that 404, and one of those must not fail the whole ad.
        if (request?.isForMainFrame != true) return
        // A form the creative sent, refused in shouldInterceptRequest; the creative itself is still there.
        if (request.method == "POST") return

        val message = error?.description?.toString().orEmpty().ifEmpty { "Unknown error" }
        Log.e(TAG, "Load error: $message")

        onPageLoadError?.invoke(error?.errorCode ?: ERROR_UNKNOWN, message)
    }

    /**
     * The process that renders this WebView crashed, or the system killed it to reclaim memory. On most devices every
     * WebView in the app shares that process, so each one hears about it, the host app's own included.
     *
     * Returning false, which is what a client that does not override this does, makes Android kill the whole app. So
     * this always returns true, and in exchange keeps the promise that comes with it: the WebView is never used again.
     * It is taken off the screen and destroyed here, before [onWebViewGone] tells its owner to end the ad. A WebView
     * of the host app's whose client returns false still ends the app; this only keeps our ads from being the reason.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val exit = processExitOf(detail)
        val cause = when (exit.didCrash) {
            true -> "crashed"
            false -> "was killed by the system"
            null -> "stopped for a reason the platform did not give"
        }
        Log.w(TAG, "WebView renderer $cause; ending the ad")

        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        onWebViewGone?.invoke(exit)
        return true
    }
}
