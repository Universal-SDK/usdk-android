package org.bidon.bidon.impl

import android.view.ViewGroup
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.config.BidonError

internal class BidonBannerImpl :
    AdSource.Banner<BidonBannerAuctionParams> {

    private var renderer: AdRenderer? = null

    private var isLoaded = false
    private var isImpressionSent = false
    private var isShown = false

    private var pendingBurl: String? = null

    override fun load(
        auction: BidonBannerAuctionParams,
        listener: AdSource.Banner.Listener
    ) {
        val adUnit = auction.adUnit

        val adm = adUnit.adm
        val crtype = adUnit.ext?.optString("crtype") ?: "HTML"
        val nurl = adUnit.nurl
        val burl = adUnit.burl

        renderer = BidonRenderer.createRendererByType(
            context = auction.activity,
            crtype = crtype
        )

        nurl?.let {
            TrackingDispatcher.fire(it)
        }

        renderer?.load(
            adm = adm,
            nurl = nurl ?: "",
            onLoaded = {
                isLoaded = true
                listener.onAdLoaded()
            },
            onClick = {
                listener.onAdClicked()
            },
            onError = { error ->
                listener.onAdLoadFailed(error)
            }
        )

        pendingBurl = burl
    }

    override fun onAdRendered(container: ViewGroup, listener: AdSource.Banner.Listener) {

        val renderer = renderer ?: run {
            listener.onAdLoadFailed(BidonError.AdNotReady)
            return
        }

        if (isShown) return
        isShown = true

        renderer.show(
            container = container,
            burl = pendingBurl ?: "",
            isFullscreen = false
        )

        listener.onAdImpression()
        isImpressionSent = true
    }

    override fun destroy() {
        renderer?.destroy()
        renderer = null

        isLoaded = false
        isShown = false
        isImpressionSent = false
        pendingBurl = null
    }
}