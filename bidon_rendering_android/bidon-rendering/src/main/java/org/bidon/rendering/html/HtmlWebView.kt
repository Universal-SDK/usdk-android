package org.bidon.rendering.html

import android.content.Context
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The container every HTML and MRAID creative renders inside.
 * [org.bidon.rendering.mraid.MraidWebView] extends this class, so every setting made here applies
 * to both. MRAID adds its bridge on top and does not change the settings below.
 *
 * A creative is untrusted third-party markup that arrived in a bid response, so each setting is
 * made explicitly rather than inherited. Platform defaults are not a stable baseline: several of
 * these changed at API 30, which would otherwise leave our behaviour depending on the device.
 */
internal open class HtmlWebView(context: Context) : WebView(context) {

    init {
        setupWebView()
    }

    // A creative is authored to fit the box it is given, so the WebView itself must never scroll.
    // Turning the scrollbars off is not enough: that hides the indicator while the view still
    // scrolls, so a creative a few pixels too tall can be dragged out of position. The ad then
    // sits half off-screen and the close button no longer lines up with the artwork, which reads
    // to the user as a broken ad. These three overrides are what actually pin it: no overscroll,
    // no programmatic scroll, no fling. Touch events still reach the creative's own JavaScript,
    // so interactive creatives and playables are unaffected.

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean = false

    override fun scrollTo(x: Int, y: Int) = Unit

    override fun computeScroll() = Unit

    protected open fun setupWebView() {
        with(settings) {
            // Creatives are scripted. MRAID needs its JS bridge, and most HTML creatives measure
            // themselves before painting.
            javaScriptEnabled = true
            domStorageEnabled = true

            // Keep the creative out of the device filesystem and content providers. mraid.js is
            // read from assets and injected as inline markup, so nothing we render needs file://
            // or content:// access.
            allowFileAccess = false
            allowContentAccess = false
            // allowFileAccessFromFileURLs and allowUniversalAccessFromFileURLs are deliberately
            // not set. Both already default to false on every API level we support, and both were
            // deprecated at API 30, so setting them changes nothing and only adds warnings.

            // A creative has no reason to ask for location, and the prompt would appear to the
            // user as if the publisher's app asked for it.
            setGeolocationEnabled(false)

            // Allow passive HTTP subresources, block active ones. NEVER_ALLOW breaks real
            // creatives that still serve images over HTTP; ALWAYS_ALLOW, which this used to be,
            // would let a creative pull scripts over plain HTTP. Creatives load through
            // loadDataWithBaseURL with a null base URL, so the page origin is opaque and this
            // mainly governs subresource loads.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Video creatives, playables especially, start themselves. At the platform default of
            // true such a creative renders and then never plays, reporting no error at all.
            mediaPlaybackRequiresUserGesture = false

            // Keep every navigation on this WebView so it reaches ClickHandler's
            // shouldOverrideUrlLoading, which is what reports the click and opens the target.
            // A creative opening its own window would bypass click reporting entirely.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // No zoom of any kind. Pinch-zooming an ad breaks its layout and there is nothing
            // in a creative a user needs to magnify.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Pin text scaling. A user running a large system font would otherwise have the
            // creative's text scaled up inside a fixed-size ad slot, overflowing the artwork.
            textZoom = 100
        }

        // The view-level half of the scroll lock that pairs with the overrides above.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollContainer = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        overScrollMode = View.OVER_SCROLL_NEVER

        setBackgroundColor(0)
    }

    fun loadHtmlContent(html: String, baseUrl: String? = null) {
        loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    fun cleanup() {
        stopLoading()
    }
}
