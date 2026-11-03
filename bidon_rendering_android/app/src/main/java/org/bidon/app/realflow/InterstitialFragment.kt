package org.bidon.app.realflow

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.demo.tetris.R
import com.demo.tetris.databinding.FragmentInterstitialBinding
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.interstitial.InterstitialAd
import org.bidon.sdk.ads.interstitial.InterstitialListener
import org.bidon.sdk.config.BidonError

/**
 * Interstitial through the real path: BidOn SDK -> live auction -> bidon adapter -> uSDK renderer.
 *
 * Load and show are separate, the way a publisher uses them: load during play, show at a break.
 */
class InterstitialFragment : Fragment(R.layout.fragment_interstitial) {

    private var _binding: FragmentInterstitialBinding? = null
    private val binding get() = _binding!!

    private var ad: InterstitialAd? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInterstitialBinding.bind(view)

        binding.btnLoadInterstitial.setOnClickListener { load() }
        binding.btnShowInterstitial.setOnClickListener { show() }
    }

    private fun load() {
        binding.btnShowInterstitial.isEnabled = false

        val interstitial = InterstitialAd()
        ad = interstitial
        interstitial.setInterstitialListener(object : InterstitialListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                _binding?.btnShowInterstitial?.isEnabled = true
            }

            override fun onAdShown(ad: Ad) = println("hop: interstitial onAdShown")
            override fun onAdClosed(ad: Ad) = println("hop: interstitial onAdClosed")
            override fun onAdClicked(ad: Ad) {}
            override fun onAdExpired(ad: Ad) {}
            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) {}
            override fun onAdShowFailed(cause: BidonError) {}
        })
        interstitial.loadAd(requireActivity())
    }

    private fun show() {
        ad?.takeIf { it.isReady() }?.showAd(requireActivity())
    }

    override fun onDestroyView() {
        ad?.destroyAd()
        ad = null
        _binding = null
        super.onDestroyView()
    }
}
