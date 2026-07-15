package org.bidon.rendering

import android.content.Context
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

    private fun applyConfig(config: RendererConfig?) {
        config ?: return

        if (config.enableDebugging) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

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