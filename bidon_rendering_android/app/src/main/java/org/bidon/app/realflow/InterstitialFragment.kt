package org.bidon.app.realflow

import android.widget.Button
import android.widget.LinearLayout
import org.bidon.app.shared.AdFragment
import org.bidon.app.shared.describe
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.interstitial.InterstitialAd
import org.bidon.sdk.ads.interstitial.InterstitialListener
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue

/**
 * The real flow for an interstitial. Load and show are separate buttons here, unlike the banner,
 * because that is how a publisher actually uses it: load early, show at a break in the game.
 */
class InterstitialFragment : AdFragment() {

    override val title = "Interstitial"

    override val subtitle =
        "Real flow. Load runs a live auction; show hands the creative to the renderer fullscreen."

    private var ad: InterstitialAd? = null
    private lateinit var showButton: Button

    override fun buildControls(container: LinearLayout) {
        container.addView(button("Load") { load() })

        showButton = button("Show") { show() }
        showButton.isEnabled = false
        container.addView(showButton)

        container.addView(
            note(
                "Known gap: the renderer has no close event yet, so onAdClosed never arrives and\n" +
                    "the ad cannot complete. Expect the log to stop after onAdShown."
            )
        )
    }

    private fun load() {
        step("interstitial 1", "loadAd() - the SDK will POST /v2/auction/interstitial")
        showButton.isEnabled = false

        val interstitial = InterstitialAd()
        ad = interstitial
        interstitial.setInterstitialListener(object : InterstitialListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                step("interstitial 2", "auction won: ${ad.describe()}")
                step("interstitial 3", "ready - tap Show")
                showButton.isEnabled = true
            }

            override fun onAdShown(ad: Ad) =
                step("interstitial 4", "onAdShown - renderer is fullscreen, impression reported")

            override fun onAdClosed(ad: Ad) =
                step("interstitial 5", "onAdClosed - the ad completed")

            override fun onAdClicked(ad: Ad) = step("interstitial", "onAdClicked")

            override fun onAdExpired(ad: Ad) = step("interstitial", "onAdExpired")

            override fun onRevenuePaid(ad: Ad, adValue: AdValue) =
                step("interstitial", "onRevenuePaid ${adValue.adRevenue} ${adValue.currency}")

            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) =
                fail("interstitial", "no ad: $cause")

            override fun onAdShowFailed(cause: BidonError) =
                fail("interstitial", "show failed: $cause")
        })
        interstitial.loadAd(requireActivity())
    }

    private fun show() {
        val interstitial = ad
        if (interstitial == null || !interstitial.isReady()) {
            fail("interstitial", "nothing loaded to show")
            return
        }
        step("interstitial", "showAd()")
        interstitial.showAd(requireActivity())
    }

    override fun onDestroyView() {
        ad?.destroyAd()
        ad = null
        super.onDestroyView()
    }
}
