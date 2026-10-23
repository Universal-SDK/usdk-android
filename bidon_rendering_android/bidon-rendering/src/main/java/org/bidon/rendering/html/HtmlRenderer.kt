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
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.LoadFailure
import org.bidon.rendering.ui.AdWindow
import androidx.core.view.doOnAttach
import org.bidon.rendering.ui.FullscreenAdActivity
import android.app.Activity
import android.content.Intent

internal class HtmlRenderer(
    context: Context,
    private val rendering: AdRenderingConfig,
    private val events: AdEvents?,
) : AdRenderer {

    private val appContext = context.applicationContext

    private var webView: HtmlWebView? = null
    private var probe: ContentProbe? = null
    private var listener: AdRenderer.Listener? = null

    private var isPageFinished = false
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
        cancelProbe()
        isPageFinished = false
        isLoaded = false
        events?.loadStarted()

        val html = injectViewportMetaTag(adm)
        lastHtml = html

        val view = HtmlWebView(appContext).apply {

            webViewClient = ClickHandler(
                context = appContext,
                onClick = {
                    this@HtmlRenderer.listener?.onClick()
                },
                // A view an earlier load left behind reports nothing: the renderer's state belongs to the latest load.
                onPageLoaded = {
                    if (webView === this && !isPageFinished) {
                        isPageFinished = true
                        probe = ContentProbe(this) { hasContent -> onProbed(this, hasContent) }.also { it.start() }
                    }
                },
                onPageLoadError = { errorCode, description ->
                    if (webView === this) {
                        // Only the creative's own load, which lasts until the page has been read, fails the load; a
                        // navigation after it does not. A failed load's view is never handed out, and the page Android
                        // shows for the error is never read, since the view is no longer the ad's.
                        val failsTheLoad = !isLoaded
                        if (failsTheLoad) {
                            cancelProbe()
                            webView = null
                            events?.loadFailed(LoadFailure.WEBVIEW_ERROR, errorCode)
                        }
                        this@HtmlRenderer.listener?.onError(Exception(description))
                        if (failsTheLoad) runCatching { this@apply.destroy() }
                    }
                },
                onWebViewGone = ::onWebViewGone
            )

            webChromeClient = WebChromeClient()

            // Shown inline once the host puts the view in a window; a fullscreen screen reports its own impression.
            doOnAttach { if (presentationKey == null && !isDestroyed) reportImpression() }
        }
        webView = view

        try {
            view.loadDataWithBaseURL(
                null,
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (e: Exception) {
            // A view that failed to take its markup is never handed out, so it cannot be shown either. It is torn
            // down last: whatever made the load throw can make this throw too, and the failure is reported first.
            webView = null
            events?.loadFailed(LoadFailure.MARKUP_REJECTED)
            listener.onError(e)
            runCatching { view.destroy() }
        }
    }

    /** A page with something on it has loaded; a blank one is a failed load, and its view is never handed out. */
    private fun onProbed(view: HtmlWebView, hasContent: Boolean) {
        probe = null
        if (hasContent) {
            isLoaded = true
            listener?.onLoaded()
            return
        }
        webView = null
        events?.loadFailed(LoadFailure.BLANK_CREATIVE)
        listener?.onError(ContentProbe.blankCreative())
        runCatching { view.destroy() }
    }

    private fun cancelProbe() {
        probe?.cancel()
        probe = null
    }

    override fun getView(): View? = webView?.also { events?.viewRequested() }

    override fun attach(container: ViewGroup) {
        val view = webView ?: return
        if (isDestroyed) return

        // Before the view is added: in a container that is already in a window the impression fires inside addView.
        events?.viewRequested()
        if (view.parent != null) {
            (view.parent as ViewGroup).removeView(view)
        }

        container.addView(view)
    }

    override fun showFullscreen(activity: Activity) {
        events?.showRequested()
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
            override val events: AdEvents? get() = this@HtmlRenderer.events
            private var rewarded = false
            override fun onImpression() = reportImpression()
            override fun onClicked() = run { listener?.onClick(); Unit }
            override fun onClosed() {
                events?.closed()
                listener?.onClose()
            }
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
    private fun onWebViewGone(didCrash: Boolean) {
        cancelProbe()
        events?.processGone(didCrash)
        webView = null
        isLoaded = false
        if (presentationKey == null) {
            listener?.onError(IllegalStateException("The WebView's renderer process is gone"))
        } else {
            PresentedAds.get(presentationKey)?.closeRequester?.invoke()
        }
    }

    private fun reportImpression() {
        events?.rendered()
        listener?.onImpression()
    }

    override fun destroy() {
        isDestroyed = true
        cancelProbe()

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