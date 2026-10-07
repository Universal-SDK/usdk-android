package org.bidon.bidon.impl

import android.util.Log
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.bidon.BidonDemandId
import org.bidon.rendering.AdType
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.rendering.AdRenderingConfig
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.bidon.RendererLoadDeadline

internal class BidonBannerImpl :
    AdSource.Banner<BidonBannerAuctionParams>,
    AdEventFlow,
    StatisticsCollector by StatisticsCollectorImpl() {

    companion object {
        private const val TAG = "BidonBanner"
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

        BidonBannerAuctionParams(
            activity = activity,
            adm = adm,
            creativeType = CreativeType.of(adm, rendering),
            rendering = rendering,
            price = adUnit.pricefloor,
            adUnit = adUnit
        )
    }

    override fun load(adParams: BidonBannerAuctionParams) {
        Log.d(TAG, "load() creativeType=${adParams.creativeType}")

        val ad = getAd()

        loadDeadline?.cancel()
        renderer?.destroy()

        renderer = BidonRenderer.createRenderer(adParams.creativeType, adParams.rendering, adInfo(AdType.BANNER))

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

            // A banner is never closed or rewarded: it is removed by the publisher, and
            // BannerView ignores both events anyway.
            override fun onClose() = Unit

            override fun onReward() = Unit

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

    override fun getAdView(): AdViewHolder? {
        val view = renderer?.getView() ?: return null
        return AdViewHolder(networkAdview = view)
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