package org.bidon.bidon.impl

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonDemandId
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.bidon.creativeTypeOf
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class BidonInterstitialImpl :
    AdSource.Interstitial<BidonFullscreenAuctionParams>,
    AdEventFlow,
    StatisticsCollector by StatisticsCollectorImpl() {

    companion object {
        private const val TAG = "BidonInterstitial"
    }

    override val adEvent = MutableSharedFlow<AdEvent>(extraBufferCapacity = 10)

    override fun emitEvent(event: AdEvent) {
        adEvent.tryEmit(event)
    }

    override var isAdReadyToShow: Boolean = false

    private var renderer: AdRenderer? = null
    private var isImpressionSent = false

    override fun getAuctionParam(
        auctionParamsScope: AdAuctionParamSource
    ): Result<AdAuctionParams> = auctionParamsScope {
        val ext = adUnit.extra

        val adm = ext?.optString("payload").orEmpty()
        val crtype = creativeTypeOf(adm)

        BidonFullscreenAuctionParams(
            activity = activity,
            adm = adm,
            crtype = crtype,
            price = adUnit.pricefloor,
            adUnit = adUnit
        )
    }

    override fun load(adParams: BidonFullscreenAuctionParams) {
        Log.d(TAG, "load() crtype=${adParams.crtype}")

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

            // Rewards do not apply to an interstitial; InterstitialImpl ignores the event.
            override fun onReward() = Unit

            override fun onError(error: Throwable) {
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
        renderer?.destroy()
        renderer = null
        isAdReadyToShow = false
        isImpressionSent = false
    }
}