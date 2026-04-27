/*package org.bidon.rendering

import android.content.Context
import android.webkit.WebView
import org.bidon.rendering.html.HtmlRenderer
import android.view.View
import android.app.Activity

object BidonRenderer {

    private var isInitialized = false
    private lateinit var appContext: Context
    private var config: RendererConfig? = null

    fun init(context: Context, config: RendererConfig? = null) {
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

        // тут потім підключиш:
        // TrackingDispatcher.setTimeout(config.networkTimeoutMs)
    }

    fun createRendererByType(
        context: Context,
        crtype: String
    ): AdRenderer {
        ensureInitialized()

        return when (crtype.uppercase()) {
            "HTML" -> createHtmlRenderer(context)
            "VAST" -> throw UnsupportedOperationException("VAST not implemented yet")
            "MRAID" -> throw UnsupportedOperationException("MRAID not implemented yet")
            else -> createHtmlRenderer(context)
        }
    }

    fun createHtmlRenderer(context: Context): AdRenderer {
        ensureInitialized()
        return HtmlRenderer(context.applicationContext)
    }

    fun createVastRenderer(context: Context): AdRenderer {
        ensureInitialized()
        throw UnsupportedOperationException("VAST not implemented yet")
    }

    fun createMraidRenderer(context: Context): AdRenderer {
        ensureInitialized()
        throw UnsupportedOperationException("MRAID not implemented yet")
    }

    private fun ensureInitialized() {
        check(isInitialized) {
            "BidonRenderer.init(context) must be called before using renderer"
        }
    }

    internal fun getConfig(): RendererConfig? = config
}

interface AdRenderer {

    fun load(
        adm: String,
        listener: Listener
    )

    fun getView(): View?

    fun showFullscreen(activity: Activity)

    fun destroy()

    interface Listener {
        fun onLoaded()
        fun onImpression()
        fun onClick()
        fun onError(error: Throwable)
    }
}*/

















package org.bidon.rendering

import android.content.Context
import android.webkit.WebView
import org.bidon.rendering.html.HtmlRenderer
import android.view.View
import android.view.ViewGroup
import android.app.Activity

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

        // TrackingDispatcher.configure(config.networkTimeoutMs)
    }

    fun createRendererByType(crtype: String): AdRenderer {
        ensureInitialized()

        return when (crtype.uppercase()) {
            "HTML" -> HtmlRenderer(appContext)

            "VAST" -> throw UnsupportedOperationException("VAST not implemented yet")

            "MRAID" -> throw UnsupportedOperationException("MRAID not implemented yet")

            else -> HtmlRenderer(appContext)
        }
    }

    fun createHtmlRenderer(): AdRenderer {
        ensureInitialized()
        return HtmlRenderer(appContext)
    }

    fun createVastRenderer(): AdRenderer {
        ensureInitialized()
        throw UnsupportedOperationException("VAST not implemented yet")
    }

    fun createMraidRenderer(): AdRenderer {
        ensureInitialized()
        throw UnsupportedOperationException("MRAID not implemented yet")
    }

    private fun ensureInitialized() {
        check(isInitialized) {
            "BidonRenderer.init(context) must be called before usage"
        }
    }

    internal fun getConfig(): RendererConfig? = config
}

interface AdRenderer {

    fun load(
        adm: String,
        listener: Listener
    )

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