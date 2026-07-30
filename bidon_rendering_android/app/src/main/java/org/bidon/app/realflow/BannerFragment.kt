package org.bidon.app.realflow

import android.graphics.Color
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.bidon.app.shared.AdFragment
import org.bidon.app.shared.describe
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.ads.banner.BannerView
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue

/**
 * The real flow for a banner:
 * publisher app -> BidOn SDK -> live auction -> bidon adapter -> uSDK renderer.
 */
class BannerFragment : AdFragment() {

    override val title = "Banner"

    override val subtitle =
        "Real flow. The SDK runs a live auction, the bidon adapter builds the renderer, " +
            "and the renderer's view is handed back to the SDK to display."

    private lateinit var slot: FrameLayout
    private var banner: BannerView? = null

    override fun buildControls(container: LinearLayout) {
        container.addView(button("Load and show") { load(BannerFormat.Banner) })
        container.addView(button("Load and show MREC") { load(BannerFormat.MRec) })
        container.addView(button("Destroy") { destroy() })

        container.addView(note("Banner slot:"))
        slot = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(250)
            )
            setBackgroundColor(Color.LTGRAY)
        }
        container.addView(slot)
    }

    private fun load(format: BannerFormat) {
        destroy()
        step("banner 1", "loadAd() - the SDK will POST /v2/auction/banner ($format)")

        val view = BannerView(context = requireContext())
        banner = view
        slot.addView(view)
        view.setBannerFormat(format)
        view.setBannerListener(object : BannerListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                step("banner 2", "auction won: ${ad.describe()}")
                step("banner 3", "adapter gave the renderer's view to the SDK, showing it")
                view.showAd()
            }

            override fun onAdShown(ad: Ad) =
                step("banner 4", "onAdShown - renderer is on screen, impression reported")

            override fun onAdClicked(ad: Ad) = step("banner", "onAdClicked")

            override fun onAdExpired(ad: Ad) = step("banner", "onAdExpired")

            override fun onRevenuePaid(ad: Ad, adValue: AdValue) =
                step("banner", "onRevenuePaid ${adValue.adRevenue} ${adValue.currency}")

            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) =
                fail("banner", "no ad: $cause")

            override fun onAdShowFailed(cause: BidonError) = fail("banner", "show failed: $cause")
        })
        view.loadAd(requireActivity(), BidonSdk.DefaultPricefloor)
    }

    private fun destroy() {
        banner?.let {
            it.destroyAd()
            slot.removeAllViews()
            banner = null
        }
    }

    override fun onDestroyView() {
        destroy()
        super.onDestroyView()
    }
}
