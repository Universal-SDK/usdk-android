package org.bidon.bidon.impl

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandId
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
    private var pendingBurl: String? = null
    private var isImpressionSent = false

    override fun getAuctionParam(
        auctionParamsScope: AdAuctionParamSource
    ): Result<AdAuctionParams> = auctionParamsScope {
        val lineItem = popLineItem(DemandId("bidon"))

        val json = json
        val adm = json?.optString("adm").orEmpty()
        val crtype = json?.optString("crtype") ?: "HTML"
        val nurl = json?.optString("nurl")?.ifBlank { null }
        val burl = json?.optString("burl")?.ifBlank { null }

        BidonFullscreenAuctionParams(
            activity = activity,
            adm = adm,
            crtype = crtype,
            nurl = nurl,
            burl = burl,
            price = lineItem?.pricefloor ?: pricefloor,
            lineItem = lineItem
        )
    }

    override fun load(adParams: BidonFullscreenAuctionParams) {
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

            override fun onError(error: Throwable) {
                Log.e(TAG, "Ad error: ${error.message}")
                isAdReadyToShow = false
                emitEvent(AdEvent.LoadFailed(BidonError.NoFill(DemandId("bidon"))))
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
        pendingBurl = null
        isAdReadyToShow = false
        isImpressionSent = false
    }
}