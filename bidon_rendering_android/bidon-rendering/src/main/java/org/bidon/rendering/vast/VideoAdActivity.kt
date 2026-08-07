package org.bidon.rendering.vast.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
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
            skipOffsetMs: Long = 5000L,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        val mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
        val clickUrl = intent.getStringExtra(EXTRA_CLICK_URL)
        val skipOffsetMs = intent.getLongExtra(EXTRA_SKIP_OFFSET_MS, 1000L)
        val adKey = intent.getStringExtra(EXTRA_AD_KEY)

        if (mediaUrl.isNullOrBlank()) {
            finish()
            return
        }

        vastAd = adKey?.let { VastRenderer.takeAd(it) }

        eventTracker.reset()
        rewardedController.reset()

        player = VideoPlayer(this).apply {
            load(mediaUrl)
        }

        val adView = VideoAdView(this).apply {
            player?.let { bindPlayer(it) }
            setClickThroughUrl(clickUrl)
            setSkipOffsetMs(skipOffsetMs)

            setListener(object : VideoAdView.Listener {
                override fun onClick() {

                }

                override fun onSkip() {
                    val trackingEvents = vastAd?.trackingEvents ?: emptyList()
                    eventTracker.onSkip(trackingEvents)
                    rewardedController.onSkip()
                    isCompleted = true
                    // Skipping ends the video, not the ad. The companion end card,
                    // when the VAST has one, still gets its turn.
                    showEndCard()
                }

                override fun onMuteChanged(isMuted: Boolean) {}

                override fun onCtaClick() {

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

    override fun onStop() {
        super.onStop()
        if (!isCompleted) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoAdView?.setListener(null)
        videoAdView?.unbindPlayer()
        videoAdView = null
        endCardView?.destroy()
        endCardView?.setListener(null)
        endCardView = null
        player?.release()
        player = null
    }

    override fun onBackPressed() {
        if (!isCompleted) return
        super.onBackPressed()
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
                rewardedController.onComplete()

                isCompleted = true
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