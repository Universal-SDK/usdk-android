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

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null

    private val mraidJs: String by lazy {
        context.assets.open("mraid.js").bufferedReader().use { it.readText() }
    }

    override fun load(adm: String, listener: AdRenderer.Listener) {
        this.listener = listener
        isDestroyed = false
        cancelProbe()
        isPageFinished = false
        isLoaded = false
        events?.loadStarted()

        val wv = MraidWebView(context.applicationContext)
        webView = wv

        val ctrl = MraidController(
            context = context.applicationContext,
            webView = wv,
            placement = MraidPlacementType.INLINE,
            closeButton = rendering.closeButton,
            // mraid.close() from the creative. While it is fullscreen the hosting activity has to
            // dismiss itself; inline there is no activity, so it is reported straight up. This
            // used to be wired to onImpression, so a creative closing itself counted as a view.
            onClose = {
                val requestClose = presentation?.closeRequester
                if (requestClose != null) {
                    requestClose()
                } else {
                    events?.closed()
                    listener.onClose()
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
        wv.doOnAttach { if (presentation == null && !isDestroyed) reportImpression() }

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
        if (webView == null || isDestroyed) {
            listener?.onError(IllegalStateException("MRAID not loaded"))
            return
        }

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
        presentation = presented
        presentationKey = PresentedAds.register(presented)

        activity.startActivity(
            Intent(activity, MraidAdActivity::class.java)
                .putExtra(PresentedAds.EXTRA_KEY, presentationKey)
        )
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
