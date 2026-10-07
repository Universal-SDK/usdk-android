package org.bidon.bidon.impl

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonDemandId
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.rendering.AdType
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.rendering.AdRenderingConfig
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.bidon.RendererLoadDeadline

internal class BidonRewardedImpl :
    AdSource.Rewarded<BidonFullscreenAuctionParams>,
    AdEventFlow,
    StatisticsCollector by StatisticsCollectorImpl() {

    companion object {
        private const val TAG = "BidonRewarded"
    }

    override val adEvent = MutableSharedFlow<AdEvent>(extraBufferCapacity = 10)

    override fun emitEvent(event: AdEvent) {
        adEvent.tryEmit(event)
    }

    override var isAdReadyToShow: Boolean = false

    private var renderer: AdRenderer? = null
    private var isImpressionSent = false

    private val scope = CoroutineScope(Dispatchers.Main)
    private var loadDeadline: RendererLoadDeadline? = null

    override fun getAuctionParam(
        auctionParamsScope: AdAuctionParamSource
    ): Result<AdAuctionParams> = auctionParamsScope {
        val ext = adUnit.extra

        val adm = ext?.optString("payload").orEmpty()
        val rendering = AdRenderingConfig.fromJson(ext?.optJSONObject("rendering"))

        BidonFullscreenAuctionParams(
            activity = activity,
            adm = adm,
            creativeType = CreativeType.of(adm, rendering),
            rendering = rendering,
            price = adUnit.pricefloor,
            adUnit = adUnit
        )
    }

    override fun load(adParams: BidonFullscreenAuctionParams) {
        Log.d(TAG, "load() creativeType=${adParams.creativeType}")

        val ad = getAd()

        loadDeadline?.cancel()
        renderer?.destroy()

        renderer = BidonRenderer.createRenderer(adParams.creativeType, adParams.rendering, adInfo(AdType.REWARDED))

        loadDeadline = RendererLoadDeadline(
            scope = scope,
            timeoutMs = adParams.adUnit.timeout,
            onTimeout = {
                Log.e(TAG, "Ad load timeout after ${adParams.adUnit.timeout} ms")

                isAdReadyToShow = false

                renderer?.destroy()
                renderer = null
                loadDeadline = null

                ad?.let {
                    emitEvent(AdEvent.LoadFailed(BidonError.NoFill(BidonDemandId)))
                }
            }
        )

        loadDeadline?.start()

        renderer?.load(adParams.adm, object : AdRenderer.Listener {
            override fun onLoaded() {
                if (loadDeadline?.finish() != true) {
                    return
                }

                loadDeadline = null

                Log.d(TAG, "Ad loaded")
                isAdReadyToShow = true
                ad?.let { emitEvent(AdEvent.Fill(it)) }
            }

            override fun onImpression() {
                if (!isImpressionSent) {
                    isImpressionSent = true
                    ad?.let { emitEvent(AdEvent.Shown(it)) }
                }
            }

            override fun onClick() {
                ad?.let { emitEvent(AdEvent.Clicked(it)) }
            }

            override fun onClose() {
                Log.d(TAG, "Ad closed")
                isAdReadyToShow = false
                ad?.let { emitEvent(AdEvent.Closed(it)) }
            }

            override fun onReward() {
                Log.d(TAG, "User rewarded")
                // Null reward matches the shipped adapters: the title and amount come from the
                // server-side ad unit, not from the renderer.
                ad?.let { emitEvent(AdEvent.OnReward(it, null)) }
            }

            override fun onError(error: Throwable) {
                if (loadDeadline?.finish() != true) {
                    return
                }

                loadDeadline = null

                Log.e(TAG, "Ad error: ${error.message}")
                isAdReadyToShow = false
                emitEvent(AdEvent.LoadFailed(BidonError.NoFill(BidonDemandId)))
            }
        })
    }

    override fun show(activity: Activity) {
        val r = renderer ?: run {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
            return
        }
        r.showFullscreen(activity)
    }

    override fun destroy() {
        loadDeadline?.cancel()
        loadDeadline = null

        renderer?.destroy()
        renderer = null
        isAdReadyToShow = false
        isImpressionSent = false
    }
}