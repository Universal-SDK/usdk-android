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
import org.bidon.rendering.mraid.ui.MraidAdActivity

class MraidRenderer(
    private val context: Context,
    private val rendering: AdRenderingConfig
) : AdRenderer {

    private var webView: MraidWebView? = null
    private var controller: MraidController? = null
    private var listener: AdRenderer.Listener? = null

    private var isDestroyed = false

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null

    private val mraidJs: String by lazy {
        context.assets.open("mraid.js").bufferedReader().use { it.readText() }
    }

    override fun load(adm: String, listener: AdRenderer.Listener) {
        this.listener = listener
        isDestroyed = false

        val wv = MraidWebView(context.applicationContext)
        webView = wv

        val ctrl = MraidController(
            context = context.applicationContext,
            webView = wv,
            placement = MraidPlacementType.INLINE,
            // mraid.close() from the creative. While it is fullscreen the hosting activity has to
            // dismiss itself; inline there is no activity, so it is reported straight up. This
            // used to be wired to onImpression, so a creative closing itself counted as a view.
            onClose = {
                val requestClose = presentation?.closeRequester
                if (requestClose != null) requestClose() else listener.onClose()
            },
            onClick = { listener.onClick() }
        )
        controller = ctrl

        wv.attachBridge(
            bridge = MraidJsBridge(ctrl),
            onClick = { listener.onClick() },
            onPageLoaded = {
                ctrl.onReady()
                listener.onLoaded()
            },
            onPageLoadError = { error ->
                listener.onError(Exception(error))
            },
            onWebViewGone = ::onWebViewGone
        )

        wv.loadMraidContent(adm, mraidJs)
    }

    override fun attach(container: ViewGroup) {
        val wv = webView ?: return
        if (isDestroyed) return

        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv)

        controller?.setPlacement(MraidPlacementType.INLINE)
        controller?.onViewabilityChanged(isViewable = true)
        listener?.onImpression()
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
    private fun onWebViewGone() {
        // Takes down an expanded ad's overlay, which the WebView was drawn in.
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
