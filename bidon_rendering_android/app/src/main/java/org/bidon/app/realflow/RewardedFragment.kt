package org.bidon.app.realflow

import android.widget.Button
import android.widget.LinearLayout
import org.bidon.app.shared.AdFragment
import org.bidon.app.shared.describe
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.ads.rewarded.RewardedAd
import org.bidon.sdk.ads.rewarded.RewardedListener
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue

/**
 * The real flow for a rewarded ad. Same shape as the interstitial, plus the reward callback,
 * which is the one that decides whether the player gets paid.
 */
class RewardedFragment : AdFragment() {

    override val title = "Rewarded"

    override val subtitle =
        "Real flow. Same path as the interstitial, and the reward callback has to fire " +
            "for the player to be paid."

    private var ad: RewardedAd? = null
    private lateinit var showButton: Button

    override fun buildControls(container: LinearLayout) {
        container.addView(button("Load") { load() })

        showButton = button("Show") { show() }
        showButton.isEnabled = false
        container.addView(showButton)

        container.addView(
            note(
                "Known gaps: the renderer has no reward event and no close event, so\n" +
                    "onUserRewarded and onAdClosed never arrive."
            )
        )
    }

    private fun load() {
        step("rewarded 1", "loadAd() - the SDK will POST /v2/auction/rewarded")
        showButton.isEnabled = false

        val rewarded = RewardedAd()
        ad = rewarded
        rewarded.setRewardedListener(object : RewardedListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                step("rewarded 2", "auction won: ${ad.describe()}")
                step("rewarded 3", "ready - tap Show")
                showButton.isEnabled = true
            }

            override fun onAdShown(ad: Ad) =
                step("rewarded 4", "onAdShown - renderer is fullscreen, impression reported")

            override fun onUserRewarded(ad: Ad, reward: Reward?) =
                step("rewarded 5", "onUserRewarded ${reward?.label} x${reward?.amount}")

            override fun onAdClosed(ad: Ad) = step("rewarded 6", "onAdClosed - the ad completed")

            override fun onAdClicked(ad: Ad) = step("rewarded", "onAdClicked")

            override fun onAdExpired(ad: Ad) = step("rewarded", "onAdExpired")

            override fun onRevenuePaid(ad: Ad, adValue: AdValue) =
                step("rewarded", "onRevenuePaid ${adValue.adRevenue} ${adValue.currency}")

            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) =
                fail("rewarded", "no ad: $cause")

            override fun onAdShowFailed(cause: BidonError) = fail("rewarded", "show failed: $cause")
        })
        rewarded.loadAd(requireActivity())
    }

    private fun show() {
        val rewarded = ad
        if (rewarded == null || !rewarded.isReady()) {
            fail("rewarded", "nothing loaded to show")
            return
        }
        step("rewarded", "showAd()")
        rewarded.showAd(requireActivity())
    }

    override fun onDestroyView() {
        ad?.destroyAd()
        ad = null
        super.onDestroyView()
    }
}
