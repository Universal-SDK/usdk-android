package org.bidon.bidon.impl

import android.util.Log
import android.view.ViewGroup
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.bidon.BidonDemandId
import org.bidon.bidon.creativeTypeOf
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
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
    private var pendingBurl: String? = null
    private var isImpressionSent = false

    override fun getAuctionParam(
        auctionParamsScope: AdAuctionParamSource
    ): Result<AdAuctionParams> = auctionParamsScope {
        val ext = adUnit.extra

        val adm = ext?.optString("payload").orEmpty()
        val crtype = creativeTypeOf(adm)
        val nurl = ext?.optString("nurl")?.ifBlank { null }
        val burl = ext?.optString("burl")?.ifBlank { null }

        BidonBannerAuctionParams(
            activity = activity,
            adm = adm,
            crtype = crtype,
            nurl = nurl,
            burl = burl,
            price = adUnit.pricefloor,
            adUnit = adUnit
        )
    }

    override fun load(adParams: BidonBannerAuctionParams) {
        Log.d(TAG, "load() crtype=${adParams.crtype}")

        pendingBurl = adParams.burl

        adParams.nurl?.let {
            Log.d(TAG, "Firing nurl")
            TrackingDispatcher.fire(it, "nurl")
        }

        val ad = getAd()

        renderer = BidonRenderer.createRendererByType(adParams.crtype)
        renderer?.load(adParams.adm, object : AdRenderer.Listener {
            override fun onLoaded() {
                Log.d(TAG, "Ad loaded")
                isAdReadyToShow = true
                ad?.let { emitEvent(AdEvent.Fill(it)) }
            }

            override fun onImpression() {
                if (!isImpressionSent) {
                    isImpressionSent = true
                    pendingBurl?.let {
                        Log.d(TAG, "Firing burl")
                        TrackingDispatcher.fire(it, "burl")
                    }
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
                emitEvent(AdEvent.LoadFailed(BidonError.NoFill(BidonDemandId)))
            }
        })
    }

    override fun getAdView(): AdViewHolder? {
        val view = renderer?.getView() ?: return null
        return AdViewHolder(networkAdview = view)
    }

    override fun destroy() {
        renderer?.destroy()
        renderer = null
        pendingBurl = null
        isAdReadyToShow = false
        isImpressionSent = false
    }
}