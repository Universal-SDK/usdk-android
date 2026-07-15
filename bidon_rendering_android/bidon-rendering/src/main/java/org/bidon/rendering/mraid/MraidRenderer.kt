package org.bidon.rendering.mraid

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.bidon.rendering.AdRenderer

class MraidRenderer(
    private val context: Context
) : AdRenderer {

    private var webView: MraidWebView? = null
    private var controller: MraidController? = null
    private var listener: AdRenderer.Listener? = null

    private var lastAdm: String? = null
    private var isDestroyed = false

    private val mraidJs: String by lazy {
        context.assets.open("mraid.js").bufferedReader().use { it.readText() }
    }

    override fun load(adm: String, listener: AdRenderer.Listener) {
        this.listener = listener
        this.lastAdm = adm
        isDestroyed = false

        val wv = MraidWebView(context.applicationContext)
        webView = wv

        val ctrl = MraidController(
            context = context.applicationContext,
            webView = wv,
            placement = MraidPlacementType.INLINE,
            onClose = { listener.onImpression() },
            onClick = { listener.onClick() }
        )
        controller = ctrl

        val bridge = MraidJsBridge(ctrl)

        wv.attachBridge(
            bridge = bridge,
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

        if (wv.parent != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
        }

        container.addView(wv)
        controller?.onViewabilityChanged(isViewable = true)
        listener?.onImpression()
    }

    override fun showFullscreen(activity: Activity) {
        val adm = lastAdm ?: run {
            listener?.onError(IllegalStateException("MRAID not loaded"))
            return
        }

        org.bidon.rendering.mraid.ui.MraidAdActivity.start(
            context = activity,
            html = adm,
            mraidJs = mraidJs
        )

        listener?.onImpression()
    }

    override fun getView(): View? = webView

    override fun destroy() {
        isDestroyed = true
        controller?.destroy()
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        controller = null
        listener = null
    }
}