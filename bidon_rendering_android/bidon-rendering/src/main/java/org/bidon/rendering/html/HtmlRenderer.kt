package org.bidon.rendering.html

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.CloseButtonConfig
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.ui.FullscreenAdActivity
import android.app.Activity
import android.content.Intent

class HtmlRenderer(
    context: Context,
    private val rendering: AdRenderingConfig
) : AdRenderer {

    private val appContext = context.applicationContext

    private var webView: HtmlWebView? = null
    private var listener: AdRenderer.Listener? = null

    private var isLoaded = false
    private var isDestroyed = false

    private var lastHtml: String? = null

    /** Key the fullscreen activity uses to find its way back here. */
    private var presentationKey: String? = null

    override fun load(
        adm: String,
        listener: AdRenderer.Listener
    ) {
        this.listener = listener
        isDestroyed = false

        val html = injectViewportMetaTag(adm)
        lastHtml = html

        webView = HtmlWebView(appContext).apply {

            webViewClient = ClickHandler(
                context = appContext,
                onClick = {
                    this@HtmlRenderer.listener?.onClick()
                },
                onPageLoaded = {
                    isLoaded = true
                    this@HtmlRenderer.listener?.onLoaded()
                },
                onPageLoadError = { error ->
                    this@HtmlRenderer.listener?.onError(Exception(error))
                },
                onWebViewGone = ::onWebViewGone
            )

            webChromeClient = WebChromeClient()

            try {
                loadDataWithBaseURL(
                    null,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            } catch (e: Exception) {
                listener.onError(e)
            }
        }
    }

    override fun getView(): View? = webView

    override fun attach(container: ViewGroup) {
        val view = webView ?: return
        if (isDestroyed) return

        if (view.parent != null) {
            (view.parent as ViewGroup).removeView(view)
        }

        container.addView(view)
        listener?.onImpression()
    }

    override fun showFullscreen(activity: Activity) {
        if (webView == null || isDestroyed) {
            listener?.onError(IllegalStateException("Ad not loaded"))
            return
        }

        // Locked to whatever the publisher's activity allows, resolved now rather than in
        // the ad activity, which cannot see the app that asked for the ad.
        val orientation = AdWindow.orientationInheritedFrom(activity)

        // The activity adopts this already-loaded WebView, keeping the ClickHandler that is
        // attached to it. It used to build a second one and reload the markup, which meant
        // fullscreen clicks and load errors were never reported.
        presentationKey = PresentedAds.register(object : PresentedAd {
            // Read when the activity starts, which is after the WebView may already be gone.
            override val contentView: View? get() = webView
            override val closeButton: CloseButtonConfig get() = rendering.closeButton
            override val lockedOrientation: Int = orientation
            override var closeRequester: (() -> Unit)? = null
            private var rewarded = false
            override fun onImpression() = run { listener?.onImpression(); Unit }
            override fun onClicked() = run { listener?.onClick(); Unit }
            override fun onClosed() = run { listener?.onClose(); Unit }
            override fun onRewarded() {
                if (rewarded) return
                rewarded = true
                listener?.onReward()
            }
            override fun onFailed(error: Throwable) = run { listener?.onError(error); Unit }
        })

        activity.startActivity(
            Intent(activity, FullscreenAdActivity::class.java)
                .putExtra(PresentedAds.EXTRA_KEY, presentationKey)
        )

        // The impression is reported by the activity once the ad is actually on screen, not here.
    }

    /**
     * The WebView's renderer process died, and [ClickHandler] has already destroyed the WebView. An ad that was never
     * shown has failed to load. An ad on a fullscreen screen closes, as if the user had closed it, and the screen
     * reports the close; a screen that is still starting finds no view and ends the ad itself. An ad already closed
     * has nothing left to report.
     */
    private fun onWebViewGone() {
        webView = null
        isLoaded = false
        if (presentationKey == null) {
            listener?.onError(IllegalStateException("The WebView's renderer process is gone"))
        } else {
            PresentedAds.get(presentationKey)?.closeRequester?.invoke()
        }
    }

    override fun destroy() {
        isDestroyed = true

        PresentedAds.unregister(presentationKey)
        presentationKey = null

        (webView?.parent as? ViewGroup)?.removeView(webView)

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        webView = null
        listener = null
    }

    private fun injectViewportMetaTag(html: String): String {
        if (html.contains("viewport", ignoreCase = true)) return html

        val tag =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">"

        return when {
            html.contains("<head>", true) ->
                html.replace("<head>", "<head>$tag", true)

            html.contains("<html>", true) ->
                html.replace("<html>", "<html><head>$tag</head>", true)

            else -> "$tag$html"
        }
    }
}