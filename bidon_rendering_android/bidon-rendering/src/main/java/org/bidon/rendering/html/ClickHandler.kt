package org.bidon.rendering.html

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/**
 * Turns a tap on a creative into a click report and a click-through.
 *
 * The rule this class holds to: the ad's WebView shows the creative and nothing else. Every
 * navigation is either handled by opening it somewhere else, or refused. It must never be allowed
 * to load the destination itself, because then the ad silently becomes a web page with a close
 * button over it.
 */
class ClickHandler(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onPageLoaded: (() -> Unit)? = null,
    private val onPageLoadError: ((String) -> Unit)? = null,
    private val onWebViewGone: (() -> Unit)? = null
) : WebViewClient() {

    private companion object {
        const val TAG = "ClickHandler"
    }

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

        Log.d(TAG, "Intercepted click: $url")

        onClick()

        openClickThrough(uri)

        // Always true. Returning false hands the URL back to the WebView, which would replace
        // the creative with the advertiser's page inside the ad container.
        return true
    }

    private fun openClickThrough(uri: Uri) {
        try {
            when (uri.scheme) {
                // Deep links into an installed app, with the creative's own fallback if the app
                // is not there.
                "intent" -> openIntentScheme(uri.toString())

                // http, https, market and anything else a creative might use go to whichever app
                // claims them. A missing scheme lands here too and simply fails to resolve.
                else -> openExternal(uri)
            }
        } catch (e: Exception) {
            // Nothing claimed it, or there is no browser on the device. The click is reported
            // either way, because the user did click; only the destination failed to open.
            Log.e(TAG, "Could not open click-through: $uri", e)
        }
    }

    private fun openIntentScheme(url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid intent url: $url", e)
            return
        }

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")

        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            if (!fallbackUrl.isNullOrEmpty()) {
                openExternal(Uri.parse(fallbackUrl))
            } else {
                Log.w(TAG, "No app and no fallback for intent")
            }
        }
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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

        val message = error?.description?.toString().orEmpty().ifEmpty { "Unknown error" }
        Log.e(TAG, "Load error: $message")

        onPageLoadError?.invoke(message)
    }

    /**
     * The process that renders this WebView crashed, or the system killed it to reclaim memory. On most devices every
     * WebView in the app shares that process, so each one hears about it, the host app's own included.
     *
     * Returning false, which is what a client that does not override this does, makes Android kill the whole app. So
     * this always returns true, and in exchange keeps the promise that comes with it: the WebView is never used again.
     * It is taken off the screen and destroyed here, before [onWebViewGone] tells its owner to end the ad.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val cause = if (detail?.didCrash() == true) "crashed" else "was killed by the system"
        Log.w(TAG, "WebView renderer $cause; ending the ad")

        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        onWebViewGone?.invoke()
        return true
    }
}
