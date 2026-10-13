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
import org.bidon.rendering.ui.AdWindow
import androidx.core.view.doOnAttach
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.LoadFailure

internal class MraidRenderer(
    private val context: Context,
    private val rendering: AdRenderingConfig,
    private val events: AdEvents?,
) : AdRenderer {

    private var webView: MraidWebView? = null
    private var controller: MraidController? = null
    private var listener: AdRenderer.Listener? = null

    private var isDestroyed = false

    private var isLoaded = false

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null

    private val mraidJs: String by lazy {
        context.assets.open("mraid.js").bufferedReader().use { it.readText() }
    }

    override fun load(adm: String, listener: AdRenderer.Listener) {
        this.listener = listener
        isDestroyed = false
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
            onPageLoaded = {
                ctrl.onReady()
                isLoaded = true
                events?.loaded()
                listener.onLoaded()
            },
            onPageLoadError = { errorCode, description ->
                // Only the creative's own load: the creative cannot load another page into the ad after it.
                if (!isLoaded) events?.loadFailed(LoadFailure.WEBVIEW_ERROR, errorCode)
                listener.onError(Exception(description))
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
            events?.loadFailed(LoadFailure.MARKUP_REJECTED)
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

    override fun attach(container: ViewGroup) {
        val wv = webView ?: return
        if (isDestroyed) return

        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv)

        controller?.setPlacement(MraidPlacementType.INLINE)
        controller?.onViewabilityChanged(isViewable = true)
    }

    override fun showFullscreen(activity: Activity) {
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

    override fun getView(): View? = webView

    /**
     * The WebView's renderer process died, and [ClickHandler] has already destroyed the WebView. An ad that was never
     * shown fullscreen, inline or not yet placed, has failed. An ad on a fullscreen screen closes, as if the user had
     * closed it, and the screen reports the close; a screen that is still starting finds no view and ends the ad
     * itself.
     */
    private fun onWebViewGone(didCrash: Boolean) {
        events?.processGone(didCrash)
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
