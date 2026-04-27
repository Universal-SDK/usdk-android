package org.bidon.bidon.impl

import android.app.Activity
import android.view.ViewGroup
import org.bidon.bidon.BidonFullscreenAdAuctionParams
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.config.BidonError

internal class BidonRewardedImpl :
    AdSource.Rewarded<BidonFullscreenAdAuctionParams> {

    private var renderer: AdRenderer? = null

    private var isLoaded = false
    private var isShown = false
    private var isRewardSent = false
    private var isImpressionSent = false

    private var pendingBurl: String? = null

    private var rewardAmount: Int = 0
    private var rewardType: String = "reward"

    override fun load(
        auction: BidonFullscreenAdAuctionParams,
        listener: AdSource.Rewarded.Listener
    ) {
        val adUnit = auction.adUnit

        val adm = adUnit.adm
        val crtype = adUnit.ext?.optString("crtype") ?: "HTML"

        val nurl = adUnit.nurl
        val burl = adUnit.burl

        rewardAmount = adUnit.ext?.optInt("reward_amount") ?: 0
        rewardType = adUnit.ext?.optString("reward_type") ?: "reward"

        renderer = BidonRenderer.createRendererByType(
            context = auction.activity,
            crtype = crtype
        )

        nurl?.let { TrackingDispatcher.fire(it) }

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

    override fun show(
        activity: Activity,
        container: ViewGroup?,
        listener: AdSource.Rewarded.Listener
    ) {
        val renderer = renderer ?: run {
            listener.onAdShowFailed(BidonError.AdNotReady)
            return
        }

        if (isShown) return
        isShown = true

        renderer.show(
            container = container,
            burl = pendingBurl ?: "",
            isFullscreen = true
        )

        listener.onAdImpression()
        isImpressionSent = true

    }

    fun grantReward(listener: AdSource.Rewarded.Listener) {
        if (isRewardSent) return
        isRewardSent = true

        listener.onAdReward(
            Reward(
                type = rewardType,
                amount = rewardAmount
            )
        )
    }

    fun onAdClosed(listener: AdSource.Rewarded.Listener) {
        listener.onAdClosed()
    }

    override fun destroy() {
        renderer?.destroy()
        renderer = null

        isLoaded = false
        isShown = false
        isRewardSent = false
        isImpressionSent = false
        pendingBurl = null
    }
}