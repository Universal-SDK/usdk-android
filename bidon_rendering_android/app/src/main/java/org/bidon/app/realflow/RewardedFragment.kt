package org.bidon.app.realflow

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.demo.tetris.R
import com.demo.tetris.databinding.FragmentRewardedBinding
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.ads.rewarded.RewardedAd
import org.bidon.sdk.ads.rewarded.RewardedListener
import org.bidon.sdk.config.BidonError

/**
 * Rewarded through the real path: BidOn SDK -> live auction -> bidon adapter -> uSDK renderer.
 *
 * Same shape as the interstitial, plus [RewardedListener.onUserRewarded], which is the callback
 * that decides whether the player gets paid.
 */
class RewardedFragment : Fragment(R.layout.fragment_rewarded) {

    private var _binding: FragmentRewardedBinding? = null
    private val binding get() = _binding!!

    private var ad: RewardedAd? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRewardedBinding.bind(view)

        binding.btnLoadRewarded.setOnClickListener { load() }
        binding.btnShowRewarded.setOnClickListener { show() }
    }

    private fun load() {
        binding.btnShowRewarded.isEnabled = false

        val rewarded = RewardedAd()
        ad = rewarded
        rewarded.setRewardedListener(object : RewardedListener {
            override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                _binding?.btnShowRewarded?.isEnabled = true
            }

            override fun onUserRewarded(ad: Ad, reward: Reward?) {}
            override fun onAdShown(ad: Ad) {}
            override fun onAdClosed(ad: Ad) {}
            override fun onAdClicked(ad: Ad) {}
            override fun onAdExpired(ad: Ad) {}
            override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) {}
            override fun onAdShowFailed(cause: BidonError) {}
        })
        rewarded.loadAd(requireActivity())
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
