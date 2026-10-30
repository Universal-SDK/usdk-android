package org.bidon.bidon.impl

import android.util.Log
import android.view.ViewGroup
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.bidon.BidonDemandId
import org.bidon.bidon.RendererLoadDeadline
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

    private val scope = MainScope()
    private var loadDeadline: RendererLoadDeadline? = null
    private var isShowCalled = false

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
        isShowCalled = false
        isImpressionSent = false

        val ad = getAd()

        renderer = BidonRenderer.createRenderer(adParams.creativeType, adParams.rendering, adInfo(AdType.BANNER))

        val listener = object : AdRenderer.Listener {
            override fun onLoaded() {
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
                Log.e(TAG, "Ad error: ${error.message}")
                isAdReadyToShow = false

                if (isImpressionSent) {
                    Log.w(TAG, "Ad runtime error after impression: ${error.message}", error)
                    return
                }

                if (isShowCalled) {
                    emitEvent(AdEvent.ShowFailed(BidonError.Unspecified(BidonDemandId, error)))
                } else {
                    emitEvent(AdEvent.LoadFailed(BidonError.NoFill(BidonDemandId)))
                }
            }
        }

        val deadline = RendererLoadDeadline(scope, adParams.adUnit.timeout, listener, onTimeout = ::onLoadTimedOut)
        loadDeadline = deadline
        renderer?.load(adParams.adm, deadline)
    }

    private fun onLoadTimedOut() {
        Log.e(TAG, "Ad load timed out")
        destroy()
        emitEvent(AdEvent.LoadFailed(BidonError.FillTimedOut(BidonDemandId)))
    }

    override fun getAdView(): AdViewHolder? {
        isShowCalled = true

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
        isShowCalled = false
    }
}