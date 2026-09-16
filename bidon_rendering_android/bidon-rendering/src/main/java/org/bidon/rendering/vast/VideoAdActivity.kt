package org.bidon.rendering.vast.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.vast.RewardedController
import org.bidon.rendering.vast.VastAd
import org.bidon.rendering.vast.VastEventTracker
import org.bidon.rendering.vast.VastRenderer
import org.bidon.rendering.vast.player.VideoPlayer

@OptIn(UnstableApi::class)
class VideoAdActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MEDIA_URL = "extra_media_url"
        private const val EXTRA_CLICK_URL = "extra_click_url"
        private const val EXTRA_SKIP_OFFSET_MS = "extra_skip_offset_ms"
        private const val EXTRA_AD_KEY = "extra_ad_key"

        fun start(
            context: Context,
            mediaUrl: String,
            clickThroughUrl: String? = null,
            skipOffsetMs: Long,
            adKey: String? = null
        ) {
            val intent = Intent(context, VideoAdActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_MEDIA_URL, mediaUrl)
                putExtra(EXTRA_CLICK_URL, clickThroughUrl)
                putExtra(EXTRA_SKIP_OFFSET_MS, skipOffsetMs)
                putExtra(EXTRA_AD_KEY, adKey)
            }
            context.startActivity(intent)
        }
    }

    private var player: VideoPlayer? = null
    private var videoAdView: VideoAdView? = null
    private var endCardView: EndCardView? = null

    private val eventTracker = VastEventTracker()
    private val rewardedController = RewardedController()

    private var vastAd: VastAd? = null
    private var isCompleted = false
    private var isEndCardShown = false

    // Back is held until the video completes or is skipped, so it cannot cut a rewarded video short
    // of its reward. A callback, not an onBackPressed override: with predictive back, on by default
    // for apps targeting Android 16, onBackPressed is never called.
    private val holdBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null
    private var impressionReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Was SCREEN_ORIENTATION_SENSOR, which invited the rotation that recreated this
        // activity and restarted the video from the beginning.
        AdWindow.goFullscreen(this)
        onBackPressedDispatcher.addCallback(this, holdBack)

        val mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
        val clickUrl = intent.getStringExtra(EXTRA_CLICK_URL)
        val skipOffsetMs = intent.getLongExtra(EXTRA_SKIP_OFFSET_MS, AdRenderingConfig.DEFAULT_SKIP_OFFSET_MS)
        val adKey = intent.getStringExtra(EXTRA_AD_KEY)

        if (mediaUrl.isNullOrBlank()) {
            finish()
            return
        }

        presentationKey = adKey
        val presented = PresentedAds.get(adKey) ?: run {
            // The renderer is gone, so nothing here could be reported: no impression, no close,
            // no reward. Showing the video anyway would be an ad nobody can account for. The
            // other two ad activities bail out the same way.
            finish()
            return
        }
        presentation = presented

        // Locked so the ad cannot rotate while it is up, restarting the video and resetting the
        // reward counter. Inherited from the publisher, resolved when the ad was shown.
        requestedOrientation = presented.lockedOrientation

        vastAd = VastRenderer.getAd(adKey)

        eventTracker.reset()
        rewardedController.reset()

        // The reward was being worked out and then dropped: setListener was never called, so
        // onUserRewarded could not fire and a rewarded video never paid the player.
        rewardedController.setListener(object : RewardedController.Listener {
            override fun onRewardEarned() {
                presentation?.onRewarded()
            }

            override fun onRewardRejected(reason: String) = Unit
        })

        player = VideoPlayer(this).apply {
            load(mediaUrl)
        }

        val adView = VideoAdView(this).apply {
            player?.let { bindPlayer(it) }
            setClickThroughUrl(clickUrl)
            setSkipOffsetMs(skipOffsetMs)

            setListener(object : VideoAdView.Listener {
                override fun onClick() {
                    presentation?.onClicked()
                }

                override fun onSkip() {
                    val trackingEvents = vastAd?.trackingEvents ?: emptyList()
                    eventTracker.onSkip(trackingEvents)
                    rewardedController.onSkip()
                    isCompleted = true
                    holdBack.isEnabled = false
                    // Skipping ends the video, not the ad. The companion end card,
                    // when the VAST has one, still gets its turn.
                    showEndCard()
                }

                override fun onMuteChanged(isMuted: Boolean) {}

                override fun onCtaClick() {
                    presentation?.onClicked()
                }
            })
        }

        videoAdView = adView
        setContentView(adView)

        observePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (isEndCardShown) return
        player?.play()
    }

    override fun onResume() {
        super.onResume()
        // Reported here rather than at startActivity, so it means the video reached the screen.
        if (!impressionReported) {
            impressionReported = true
            presentation?.onImpression()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isCompleted) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) {
            presentation?.onClosed()
            PresentedAds.unregister(presentationKey)
            presentationKey?.let { VastRenderer.adRegistry.remove(it) }
        }
        presentation = null

        videoAdView?.setListener(null)
        videoAdView?.unbindPlayer()
        videoAdView = null
        endCardView?.destroy()
        endCardView?.setListener(null)
        endCardView = null
        player?.release()
        player = null
    }

    private fun observePlayer() {
        player?.addListener(object : VideoPlayer.Listener {

            override fun onReady() {}
            override fun onStarted() {}
            override fun onPaused() {}

            override fun onProgress(positionMs: Long, durationMs: Long) {
                if (durationMs <= 0) return

                val progress = positionMs.toFloat() / durationMs.toFloat()
                val trackingEvents = vastAd?.trackingEvents ?: emptyList()
                eventTracker.onProgress(progress, durationMs, positionMs, trackingEvents)
                rewardedController.onProgress(progress)
            }

            override fun onCompleted() {
                val trackingEvents = vastAd?.trackingEvents ?: emptyList()
                val durationMs = player?.getDuration() ?: 0L
                eventTracker.onProgress(1f, durationMs, durationMs, trackingEvents)

                // The tracker is told the video reached the end; the reward controller has to be
                // told too. Its threshold is full completion, and the player's progress ticks
                // never land exactly on the duration, so without this the reward was always
                // refused with "completion threshold not reached".
                rewardedController.onProgress(1f)
                rewardedController.onComplete()

                isCompleted = true
                holdBack.isEnabled = false
                showEndCard()
            }

            override fun onError(error: Throwable) {
                finish()
            }
        })
    }

    private fun showEndCard() {
        if (isEndCardShown) return

        // Whether the video completed or was skipped, it stops here. Without this the
        // audio keeps running behind the end card.
        player?.pause()
        videoAdView?.setListener(null)
        videoAdView?.unbindPlayer()

        val companion = vastAd?.companions?.firstOrNull()

        if (companion == null) {
            finish()
            return
        }

        isEndCardShown = true

        val endCard = EndCardView(this).apply {
            setListener(object : EndCardView.Listener {
                override fun onClose() {
                    finish()
                }

                override fun onClick() {
                    presentation?.onClicked()
                }

                override fun onError(error: Throwable) {
                    finish()
                }
            })

            showCompanion(companion)
        }

        endCardView = endCard

        // Replace video view with end card
        setContentView(endCard)

        eventTracker.onCompanionView(companion.trackingEvents)
    }
}