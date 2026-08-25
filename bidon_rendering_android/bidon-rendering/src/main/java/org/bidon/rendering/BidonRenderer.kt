package org.bidon.rendering

import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import org.bidon.rendering.html.HtmlRenderer
import android.view.View
import android.view.ViewGroup
import android.app.Activity
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.vast.VastRenderer

object BidonRenderer {

    private var isInitialized = false
    private lateinit var appContext: Context
    private var config: RendererConfig? = null

    fun init(
        context: Context,
        config: RendererConfig? = null
    ) {
        if (isInitialized) return

        appContext = context.applicationContext
        this.config = config

        applyConfig(config)

        isInitialized = true
    }

    /**
     * WebView remote debugging is a process-wide switch: it exposes every WebView in the host app
     * over Chrome DevTools, not only ours. It used to be turned on by the `debug` flag in the
     * server's config response, which meant a server-side value could expose a production
     * publisher's app. It is now tied to the host app being debuggable, which a released app
     * never is.
     */
    private fun applyConfig(config: RendererConfig?) {
        config ?: return

        if (isHostAppDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun isHostAppDebuggable(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun createRendererByType(crtype: String): AdRenderer {
        ensureInitialized()

        return when (crtype.uppercase()) {
            "HTML"  -> HtmlRenderer(appContext)
            "VAST"  -> VastRenderer(appContext)
            "MRAID" -> MraidRenderer(appContext)
            else    -> HtmlRenderer(appContext)
        }
    }

    fun createHtmlRenderer(): AdRenderer {
        ensureInitialized()
        return HtmlRenderer(appContext)
    }

    fun createVastRenderer(): AdRenderer {
        ensureInitialized()
        return VastRenderer(appContext)
    }

    fun createMraidRenderer(): AdRenderer {
        ensureInitialized()
        return MraidRenderer(appContext)
    }

    private fun ensureInitialized() {
        check(isInitialized) {
            "BidonRenderer.init(context) must be called before usage"
        }
    }

    internal fun getConfig(): RendererConfig? = config
}

interface AdRenderer {

    fun load(adm: String, listener: Listener)

    fun attach(container: ViewGroup)

    fun showFullscreen(activity: Activity)

    fun destroy()

    fun getView(): View?

    interface Listener {
        fun onLoaded()
        fun onImpression()
        fun onClick()
        fun onError(error: Throwable)
    }
}