package org.bidon.rendering.mraid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.mraid.ui.MraidAdActivity

class MraidRenderer(
    private val context: Context
) : AdRenderer {

    private companion object {
        // Hardcoded until the DSP's rendering config supplies it (BID-51).
        const val CLOSE_DELAY_MS = 5000L
    }

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
            }
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
        val view = webView.takeUnless { isDestroyed } ?: run {
            listener?.onError(IllegalStateException("MRAID not loaded"))
            return
        }

        // Fullscreen means interstitial placement, which forbids expand and resize. Only the
        // permitted transitions change; the loaded creative is reused as it is.
        controller?.setPlacement(MraidPlacementType.INTERSTITIAL)

        val orientation = AdWindow.orientationInheritedFrom(activity)

        val presented = object : PresentedAd {
            override val contentView: View get() = view
            override val closeDelayMs: Long get() = CLOSE_DELAY_MS
            override val lockedOrientation: Int = orientation
            override var closeRequester: (() -> Unit)? = null
            override fun onImpression() = run { listener?.onImpression(); Unit }
            override fun onClicked() = run { listener?.onClick(); Unit }
            override fun onClosed() = run { listener?.onClose(); Unit }
            override fun onRewarded() = run { listener?.onReward(); Unit }
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
