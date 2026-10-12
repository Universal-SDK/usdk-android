package org.bidon.rendering

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens a creative's links the way a browser does. Every click-through goes through here, whichever kind of creative
 * it comes from.
 *
 * Links are started from the host app's own context, and the app may start any of its own screens, exported or not. So
 * only an activity that accepts links from the web may open one, and no link other than a plain http or https one
 * reaches a screen of the host that is not exported: a creative is not the app.
 */
internal class LinkOpener(private val context: Context) {

    private companion object {
        const val TAG = "LinkOpener"
        val WEB_SCHEMES = setOf("http", "https")
    }

    fun open(url: String) = open(Uri.parse(url))

    /** Opens [uri], or logs why nothing could: a link that does not open never fails the ad. */
    fun open(uri: Uri) {
        try {
            when (uri.scheme) {
                // Deep links into an installed app, with the creative's own fallback if the app is not there.
                "intent" -> openIntentScheme(uri.toString())

                // http, https, market and anything else a creative might use go to whichever app claims them. A
                // missing scheme lands here too and simply fails to resolve.
                else -> openExternal(uri)
            }
        } catch (e: Exception) {
            // Nothing claimed it, or there is no browser on the device. The click is reported either way, because
            // the user did click; only the destination failed to open.
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
     * Opens [intent] as a link from the web, and throws [ActivityNotFoundException] when nothing may take it. Only an
     * activity that accepts links from the web may open it and, unless [mayReachHost], none of the host's screens that
     * are not exported, whatever links they accept.
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
}
