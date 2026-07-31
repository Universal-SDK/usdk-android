package org.bidon.app.realflow

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.demo.tetris.R
import com.demo.tetris.databinding.FragmentBannerBinding
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.ads.banner.BannerView
import org.bidon.sdk.config.BidonError

/**
 * Banner through the real path: BidOn SDK -> live auction -> bidon adapter -> uSDK renderer.
 *
 * A banner is a view rather than an activity, so the adapter hands the renderer's view back to
 * the SDK, and the SDK puts it inside this [BannerView].
 */
class BannerFragment : Fragment(R.layout.fragment_banner) {

    private var _binding: FragmentBannerBinding? = null
    private val binding get() = _binding!!

    private var banner: BannerView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentBannerBinding.bind(view)

        binding.btnLoadBanner.setOnClickListener { load(BannerFormat.Banner) }
        binding.btnLoadMrec.setOnClickListener { load(BannerFormat.MRec) }
        binding.btnDestroyBanner.setOnClickListener { destroy() }
    }

    private fun load(format: BannerFormat) {
        destroy()

        val bannerView = BannerView(context = requireContext())
        banner = bannerView
        binding.bannerSlot.addView(bannerView)
        bannerView.setBannerFormat(format)
        bannerView.setBannerListener(object : BannerListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                bannerView.showAd()
            }

            override fun onAdShown(ad: Ad) {}
            override fun onAdClicked(ad: Ad) {}
            override fun onAdExpired(ad: Ad) {}
            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) {}
            override fun onAdShowFailed(cause: BidonError) {}
        })
        bannerView.loadAd(requireActivity(), BidonSdk.DefaultPricefloor)
    }

    private fun destroy() {
        banner?.destroyAd()
        _binding?.bannerSlot?.removeAllViews()
        banner = null
    }

    override fun onDestroyView() {
        destroy()
        _binding = null
        super.onDestroyView()
    }
}
