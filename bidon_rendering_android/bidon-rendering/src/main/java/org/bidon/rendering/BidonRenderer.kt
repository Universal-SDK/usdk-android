package org.bidon.rendering

import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import org.bidon.rendering.html.HtmlRenderer
import android.view.View
import android.view.ViewGroup
import android.app.Activity
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.vast.VastRenderer

object BidonRenderer {

    private var isInitialized = false
    private lateinit var appContext: Context
    private var config: RendererConfig? = null

    /** Where the renderer reports telemetry events. Safe to call from any thread, the ad path included. */
    @Volatile
    internal var telemetry: Telemetry = Telemetry.Disabled
        private set

    /**
     * Idempotent, and safe to call from several threads at once: the adapter calls it from the mediation SDK's
     * initialisation, and a host app may call it too. Only the first call takes effect, so telemetry is started once
     * and one process never has two writers on its queue.
     */
    @Synchronized
    fun init(
        context: Context,
        config: RendererConfig? = null
    ) {
        if (isInitialized) return

        appContext = context.applicationContext
        this.config = config

        applyConfig(config)
        telemetry = RendererTelemetry.start(appContext)

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

    /**
     * A renderer for one ad. [rendering] is the DSP's config for this bid; one renderer shows one
     * ad, so it keeps that config for its whole life. Without one it uses the server's defaults.
     */
    fun createRenderer(
        type: CreativeType,
        rendering: AdRenderingConfig = AdRenderingConfig()
    ): AdRenderer {
        ensureInitialized()

        return when (type) {
            CreativeType.HTML -> HtmlRenderer(appContext, rendering)
            CreativeType.VAST -> VastRenderer(appContext, rendering)
            CreativeType.MRAID -> MraidRenderer(appContext, rendering)
        }
    }

    fun createHtmlRenderer(rendering: AdRenderingConfig = AdRenderingConfig()): AdRenderer =
        createRenderer(CreativeType.HTML, rendering)

    fun createVastRenderer(rendering: AdRenderingConfig = AdRenderingConfig()): AdRenderer =
        createRenderer(CreativeType.VAST, rendering)

    fun createMraidRenderer(rendering: AdRenderingConfig = AdRenderingConfig()): AdRenderer =
        createRenderer(CreativeType.MRAID, rendering)

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

    /**
     * The renderer only detects and reports. It fires no DSP URLs of its own; the adapter maps
     * these to the mediation SDK's events and the server fires the callbacks.
     */
    interface Listener {
        fun onLoaded()
        fun onImpression()
        fun onClick()

        /**
         * The ad is finished and its activity has gone. Interstitial and rewarded cannot complete
         * without this: the publisher never learns the ad ended and cannot resume its content.
         */
        fun onClose()

        /**
         * The user earned the reward. Only meaningful for a rewarded placement; the renderer
         * decides when it is earned from the creative type.
         */
        fun onReward()

        fun onError(error: Throwable)
    }
}