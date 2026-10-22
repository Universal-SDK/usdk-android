package org.bidon.rendering

import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import org.json.JSONObject
import org.bidon.rendering.html.HtmlRenderer
import android.view.View
import android.view.ViewGroup
import android.app.Activity
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.telemetry.TelemetryBlock
import org.bidon.rendering.telemetry.TelemetrySettings
import org.bidon.rendering.vast.VastRenderer

object BidonRenderer {

    private var isInitialized = false
    private lateinit var appContext: Context
    private var config: RendererConfig? = null

    /** Telemetry settings that arrived before [init], applied by it. */
    private var pendingSettings = TelemetrySettings.NONE

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
        telemetry.applySettings(pendingSettings)
        pendingSettings = TelemetrySettings.NONE

        isInitialized = true
    }

    /**
     * Gives the renderer the `telemetry` block of the adapter's `/v2/config` section: where to send events, as whom, and
     * the server's switches. [bundle] is the host app's package name and [sdkVersion] the version sent with every
     * batch; [fallbackAppKey] is used when the block has no app key. Call it as often as config arrives, before or
     * after [init], from any thread. Returns at once; telemetry the manifest left off ignores it.
     */
    @Synchronized
    fun configureTelemetry(block: JSONObject?, bundle: String, sdkVersion: String, fallbackAppKey: String?) {
        val settings = TelemetryBlock.parse(block, bundle, sdkVersion, fallbackAppKey)
        if (isInitialized) telemetry.applySettings(settings) else pendingSettings = settings.over(pendingSettings)
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
     * [ad] labels the renderer's telemetry events, and is read once, here; without it they carry no auction or
     * ad type.
     */
    fun createRenderer(
        type: CreativeType,
        rendering: AdRenderingConfig = AdRenderingConfig(),
        ad: AdInfo? = null,
    ): AdRenderer {
        ensureInitialized()

        val events = RendererTelemetry.adEvents(telemetry, type, ad)
        return when (type) {
            CreativeType.HTML -> HtmlRenderer(appContext, rendering, events)
            CreativeType.VAST -> VastRenderer(appContext, rendering, events)
            CreativeType.MRAID -> MraidRenderer(appContext, rendering, events)
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
         * Reported for every fullscreen ad, whatever the placement: the renderer does not know it.
         * Pay the user only when the placement is rewarded; anything else ignores this. Reported at
         * most once per show: a video when it completes, a display or playable ad when its close
         * button becomes available.
         */
        fun onReward()

        fun onError(error: Throwable)
    }
}