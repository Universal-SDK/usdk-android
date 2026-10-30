package org.bidon.rendering.mraid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.CloseButtonConfig
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.failShow
import org.bidon.rendering.html.ContentProbe
import org.bidon.rendering.html.forWebViewError
import org.bidon.rendering.ui.AdWindow
import androidx.core.view.doOnAttach
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.ErrorCode
import org.bidon.rendering.telemetry.ProcessExit

internal class MraidRenderer(
    private val context: Context,
    private val rendering: AdRenderingConfig,
    private val events: AdEvents?,
) : AdRenderer {

    private var webView: MraidWebView? = null
    private var controller: MraidController? = null
    private var probe: ContentProbe? = null
    private var listener: AdRenderer.Listener? = null

    private var isDestroyed = false

    private var isPageFinished = false
    private var isLoaded = false

    /** The render process died after the ad loaded, which [onWebViewGone] tells apart from a load that never ended. */
    private var isGoneAfterLoad = false

    /** The host showed the view in its own window, where it had its impression, so the ad has been shown. */
    private var isShownInline = false

    /**
     * Key the fullscreen activity uses to find its way back here. Stays set after the screen closes, since an ad is
     * shown once; cleared only when the screen could not start, or by [destroy].
     */
    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null

    /**
     * The creative closed itself after the show and before its screen started: the screen closes as soon as it starts.
     */
    private var isClosedBeforeScreen = false

    private val mraidJs: String by lazy {
        context.assets.open("mraid.js").bufferedReader().use { it.readText() }
    }

    override fun load(adm: String, listener: AdRenderer.Listener) {
        this.listener = listener
        isDestroyed = false
        cancelProbe()
        isPageFinished = false
        isLoaded = false
        isGoneAfterLoad = false
        isShownInline = false
        events?.loadStarted()

        val wv = MraidWebView(context.applicationContext)
        webView = wv

        val ctrl = MraidController(
            context = context.applicationContext,
            webView = wv,
            placement = MraidPlacementType.INLINE,
            closeButton = rendering.closeButton,
            // mraid.close() from the creative. While it is fullscreen the hosting activity has to
            // dismiss itself, and reports the close; a screen still starting does so as soon as it
            // can. Inline there is no activity, so it is reported straight up. This used to be
            // wired to onImpression, so a creative closing itself counted as a view.
            onClose = {
                val shown = presentation
                if (shown == null) {
                    events?.closed()
                    listener.onClose()
                } else {
                    val requestClose = shown.closeRequester
                    if (requestClose != null) requestClose() else isClosedBeforeScreen = true
                }
            }
        )
        controller = ctrl

        wv.attachBridge(
            bridge = MraidJsBridge(ctrl),
            onClick = { listener.onClick() },
            // A view an earlier load left behind reports nothing: the renderer's state belongs to the latest load.
            onPageLoaded = {
                if (webView === wv && !isPageFinished) {
                    isPageFinished = true
                    val pageProbe = ContentProbe(wv) { hasContent -> onProbed(ctrl, wv, hasContent) }
                    probe = pageProbe
                    // Read after the creative has heard `ready`, which many creatives wait for to draw anything.
                    ctrl.onReady(afterReady = { if (probe === pageProbe) pageProbe.start() })
                }
            },
            onPageLoadError = { errorCode, description ->
                if (webView === wv) {
                    // Only the creative's own load, which lasts until the page has been read, fails the load: the
                    // creative cannot load another page into the ad after it. A failed load's view is never handed out,
                    // and the page Android shows for the error is never read, since the view is no longer the ad's.
                    val failsTheLoad = !isLoaded
                    if (failsTheLoad) {
                        cancelProbe()
                        controller = null
                        webView = null
                        events?.loadFailed(ErrorCode.forWebViewError(errorCode), errorCode.toString())
                    }
                    listener.onError(Exception(description))
                    if (failsTheLoad) dropView(ctrl, wv)
                }
            },
            onWebViewGone = ::onWebViewGone
        )

        // Shown inline once the host puts the view in a window; a fullscreen screen reports its own impression.
        wv.doOnAttach {
            if (presentation == null && !isDestroyed) {
                isShownInline = true
                reportImpression()
            }
        }

        // Read apart from the markup: the bridge script missing from our own package is not the creative's failure.
        val script = try {
            mraidJs
        } catch (e: Exception) {
            controller = null
            webView = null
            listener.onError(e)
            dropView(ctrl, wv)
            return
        }
        try {
            wv.loadMraidContent(adm, script)
        } catch (e: Exception) {
            controller = null
            webView = null
            events?.loadFailed(ErrorCode.MARKUP_REJECTED, cause = e)
            listener.onError(e)
            dropView(ctrl, wv)
        }
    }

    /**
     * Tears down a view that failed to load before anyone could be handed it. Called after the failure is reported:
     * whatever made the load throw can make this throw too.
     */
    private fun dropView(ctrl: MraidController, wv: MraidWebView) {
        runCatching {
            ctrl.destroy()
            wv.destroy()
        }
    }

    /** A page with something on it has loaded; a blank one is a failed load, and its view is never handed out. */
    private fun onProbed(ctrl: MraidController, wv: MraidWebView, hasContent: Boolean) {
        probe = null
        if (hasContent) {
            isLoaded = true
            events?.loadSucceeded()
            listener?.onLoaded()
            return
        }
        controller = null
        webView = null
        events?.loadFailed(ErrorCode.BLANK_CREATIVE)
        listener?.onError(ContentProbe.blankCreative())
        dropView(ctrl, wv)
    }

    private fun cancelProbe() {
        probe?.cancel()
        probe = null
    }

    override fun attach(container: ViewGroup) {
        val wv = webView ?: return
        if (isDestroyed) return

        // Before the view is added: in a container that is already in a window the impression fires inside addView.
        events?.viewRequested()
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv)

        controller?.setPlacement(MraidPlacementType.INLINE)
        controller?.onViewabilityChanged(isViewable = true)
    }

    override fun showFullscreen(activity: Activity) {
        events?.showRequested()
        readyCheckFailure()?.let { return failShow(events, listener, it) }
        events?.showStarted()

        // Fullscreen means interstitial placement, which forbids expand and resize. Only the
        // permitted transitions change; the loaded creative is reused as it is.
        controller?.setPlacement(MraidPlacementType.INTERSTITIAL)

        val orientation = AdWindow.orientationInheritedFrom(activity)

        val presented = object : PresentedAd {
            // Read when the activity starts, which is after the WebView may already be gone.
            override val contentView: View? get() = webView
            override val closeButton: CloseButtonConfig get() = rendering.closeButton
            override val lockedOrientation: Int = orientation
            override var closeRequester: (() -> Unit)? = null
                set(value) {
                    field = value
                    if (value != null && isClosedBeforeScreen) value()
                }
            override val events: AdEvents? get() = this@MraidRenderer.events
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
        }
        isClosedBeforeScreen = false
        presentation = presented
        presentationKey = PresentedAds.register(presented)

        try {
            activity.startActivity(
                Intent(activity, MraidAdActivity::class.java)
                    .putExtra(PresentedAds.EXTRA_KEY, presentationKey)
            )
        } catch (e: Exception) {
            PresentedAds.unregister(presentationKey)
            presentationKey = null
            presentation = null
            failShow(events, listener, ErrorCode.AD_SCREEN_NOT_STARTED, cause = e)
        }
    }

    /**
     * Why the ad cannot be shown now, or `null` when it can. A destroyed ad, one shown before, on a full screen or in
     * the host's own window, and one whose render process died after it loaded are told apart from one that has not
     * loaded, in that order.
     */
    private fun readyCheckFailure(): ErrorCode? = when {
        isDestroyed -> ErrorCode.SHOW_NOT_READY
        presentationKey != null || isShownInline -> ErrorCode.SHOW_TWICE
        isGoneAfterLoad -> ErrorCode.WEBVIEW_GONE_AT_SHOW
        !isLoaded || webView == null -> ErrorCode.SHOW_NOT_READY
        else -> null
    }

    override fun getView(): View? = webView?.also { events?.viewRequested() }

    /**
     * The WebView's renderer process died, and [ClickHandler] has already destroyed the WebView. An ad that was never
     * shown fullscreen, inline or not yet placed, has failed. An ad on a fullscreen screen closes, as if the user had
     * closed it, and the screen reports the close; a screen that is still starting finds no view and ends the ad
     * itself.
     */
    private fun onWebViewGone(exit: ProcessExit) {
        cancelProbe()
        events?.processGone(exit)
        // Takes down an expanded ad's dialog, which the WebView was drawn in.
        controller?.destroy()
        controller = null
        webView = null
        isGoneAfterLoad = isLoaded
        val shown = presentation
        if (shown == null) {
            listener?.onError(IllegalStateException("The WebView's renderer process is gone"))
        } else {
            shown.closeRequester?.invoke()
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
        presentation = null

        controller?.destroy()
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        controller = null
        listener = null
    }
}
