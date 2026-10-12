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
internal class ClickHandler(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onPageLoaded: (() -> Unit)? = null,
    private val onPageLoadError: ((errorCode: Int, description: String) -> Unit)? = null,
    private val onWebViewGone: ((didCrash: Boolean) -> Unit)? = null
) : WebViewClient() {

    private companion object {
        const val TAG = "ClickHandler"
        val WEB_SCHEMES = setOf("http", "https")
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

    /**
     * Opens an `intent:` link the way a browser does. A component or a selector in it would choose the screen itself,
     * and its flags how that screen starts, so all three are dropped and the link opens through [openLink]. A link
     * without an address could only be taken by screens that accept any link at all, behind the system's app chooser,
     * so it goes straight to its fallback page.
     */
    private fun openIntentScheme(url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid intent url: $url", e)
            return
        }
        intent.component = null
        intent.selector = null
        intent.flags = 0

        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
        if (intent.data == null) {
            openFallback(fallbackUrl)
            return
        }

        try {
            openLink(intent)
        } catch (e: ActivityNotFoundException) {
            openFallback(fallbackUrl)
        } catch (e: SecurityException) {
            // The app that took it requires a permission we do not hold.
            openFallback(fallbackUrl)
        }
    }

    /** Opens the link's fallback, which, as in a browser, may only be a web page. */
    private fun openFallback(fallbackUrl: String?) {
        val page = fallbackUrl?.let { Uri.parse(it).normalizeScheme() }?.takeIf { it.scheme in WEB_SCHEMES }
        if (page != null) {
            openExternal(page)
        } else {
            Log.w(TAG, "Nothing may open the link, and it has no web page to fall back to")
        }
    }

    /** A web link stays one even where the host routes that site to a screen of its own: the system chooses. */
    private fun openExternal(uri: Uri) =
        openLink(Intent(Intent.ACTION_VIEW, uri), mayReachHost = uri.scheme in WEB_SCHEMES)

    /**
     * Opens [intent] as a link from the web, and throws [ActivityNotFoundException] when nothing may take it.
     *
     * It is started from the host app's own context, and the app may start any of its own screens, exported or not. So
     * only an activity that accepts links from the web may open it and, unless [mayReachHost], none of the host's
     * screens that are not exported, whatever links they accept: a creative is not the app.
     */
    private fun openLink(intent: Intent, mayReachHost: Boolean = false) {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        if (!mayReachHost) refuseHostScreens(intent)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refuseHostScreens(intent: Intent) {
        // By user id rather than package: apps that share the host's user id may start its closed screens too.
        val hostUid = context.applicationInfo.uid
        val reachesHostScreen = context.packageManager.queryIntentActivities(intent, 0).any {
            it.activityInfo.applicationInfo.uid == hostUid && !it.activityInfo.exported
        }
        if (reachesHostScreen) throw ActivityNotFoundException("A link may not open the host app's own screens")
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
        val didCrash = detail?.didCrash() == true
        val cause = if (didCrash) "crashed" else "was killed by the system"
        Log.w(TAG, "WebView renderer $cause; ending the ad")

        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        onWebViewGone?.invoke(didCrash)
        return true
    }
}
