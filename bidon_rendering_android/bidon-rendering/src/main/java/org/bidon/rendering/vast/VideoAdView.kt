package org.bidon.rendering.vast.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.ui.clickOnlyOnTap
import org.bidon.rendering.ui.dp
import org.bidon.rendering.ui.insetFromSafeArea
import org.bidon.rendering.vast.player.VideoPlayer

@UnstableApi
class VideoAdView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onClick()
        fun onSkip()
        fun onMuteChanged(isMuted: Boolean)
        fun onCtaClick()
    }

    private var listener: Listener? = null
    private var player: VideoPlayer? = null

    private val playerView: PlayerView
    private val countdownView: TextView
    private val skipButton: TextView
    private val muteButton: ImageButton
    private val ctaButton: TextView

    private var clickThroughUrl: String? = null
    private var skipOffsetMs = AdRenderingConfig.DEFAULT_SKIP_OFFSET_MS
    private var skipEnabled = false

    init {
        setBackgroundColor(Color.BLACK)

        playerView = PlayerView(context).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        countdownView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(0x66000000)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }

        skipButton = TextView(context).apply {
            text = "Skip"
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener {
                listener?.onSkip()
            }
        }

        muteButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            setBackgroundColor(0x66000000)
            layoutParams = LayoutParams(
                dp(AdWindow.TOUCH_TARGET_DP),
                dp(AdWindow.TOUCH_TARGET_DP)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            setOnClickListener { toggleMute() }
        }

        ctaButton = TextView(context).apply {
            text = "Learn More"
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1976D2.toInt())
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            setOnClickListener {
                listener?.onCtaClick()
                openClickThrough()
            }
        }

        // z-order: playerView (bottom) → clickOverlay → countdownView → skipButton → muteButton → ctaButton (top)
        addView(playerView)

        addView(View(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                listener?.onClick()
                openClickThrough()
            }
            clickOnlyOnTap()
        })

        addView(countdownView)
        addView(skipButton)
        addView(muteButton)
        addView(ctaButton)

        // The video runs edge to edge behind the notch and system bars. The controls do not:
        // margins used to be raw pixels, so on a dense screen they sat almost against the edge,
        // and the countdown carried a hardcoded 100px top padding standing in for a status bar.
        listOf(countdownView, skipButton, muteButton, ctaButton).forEach {
            it.insetFromSafeArea(AdWindow.CONTROL_MARGIN_DP)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun bindPlayer(player: VideoPlayer) {
        this.player = player
        playerView.player = player.getExoPlayer()

        skipEnabled = false
        skipButton.visibility = View.GONE

        player.addListener(object : VideoPlayer.Listener {

            override fun onReady() {}

            override fun onStarted() {
                // Do NOT reset skipEnabled here — skip offset may already have passed
            }

            override fun onPaused() {}

            override fun onCompleted() {
                skipButton.visibility = View.GONE
            }

            override fun onProgress(positionMs: Long, durationMs: Long) {
                updateCountdown(positionMs, durationMs)
                handleSkip(positionMs)

                if (durationMs > 0 && !clickThroughUrl.isNullOrBlank()) {
                    ctaButton.visibility = View.VISIBLE
                }
            }

            override fun onError(error: Throwable) {}
        })
    }

    fun unbindPlayer() {
        playerView.player = null
        player = null
    }

    fun setClickThroughUrl(url: String?) {
        clickThroughUrl = url
        ctaButton.visibility = if (url.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun setSkipOffsetMs(offset: Long) {
        skipOffsetMs = offset
    }

    private fun updateCountdown(positionMs: Long, durationMs: Long) {
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        val sec = remaining / 1000
        countdownView.text = String.format("%02d:%02d", sec / 60, sec % 60)
    }

    private fun handleSkip(positionMs: Long) {
        if (skipEnabled) return
        if (positionMs >= skipOffsetMs) {
            skipEnabled = true
            skipButton.visibility = View.VISIBLE
        }
    }

    private fun toggleMute() {
        val p = player ?: return
        val muted = !p.isMuted()
        p.setMuted(muted)
        listener?.onMuteChanged(muted)
        muteButton.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    private fun openClickThrough() {
        val url = clickThroughUrl
        if (url.isNullOrBlank()) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
        }
    }
}