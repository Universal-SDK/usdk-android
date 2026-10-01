package org.bidon.rendering.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds

/**
 * Hosts a fullscreen HTML creative.
 *
 * The activity does not build or load anything. It adopts the view the renderer already loaded and
 * reports what happens back through [PresentedAd], which is what lets close and click reach the
 * publisher.
 */
internal class FullscreenAdActivity : AppCompatActivity() {

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null
    private var adView: View? = null

    private var impressionReported = false
    // Back is held until the close button is available, so the ad cannot be dismissed by an
    // accidental press before it has been seen. A callback, not an onBackPressed override: with
    // predictive back, on by default for apps targeting Android 16, onBackPressed is never called.
    private val holdBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    /** When the close button becomes usable, kept so a recreated activity carries on counting. */
    private var closeAvailableAtMs = 0L

    /** The close button is available, so the user has earned the reward. */
    private var rewardEarned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        AdWindow.goFullscreen(this)
        onBackPressedDispatcher.addCallback(this, holdBack)

        presentationKey = intent.getStringExtra(PresentedAds.EXTRA_KEY)
        val ad = PresentedAds.get(presentationKey) ?: run {
            // The renderer was destroyed before we got here. Nothing to show and nobody to tell.
            finish()
            return
        }
        presentation = ad
        impressionReported = savedInstanceState?.impressionReported() ?: false

        // Locked so the ad cannot rotate while it is up, restarting the creative and moving the
        // close button. Inherited from the publisher, resolved when the ad was shown.
        requestedOrientation = ad.lockedOrientation

        val view = ad.contentView ?: run {
            // Its WebView is gone, for instance because its renderer process died. An ad that was never on screen
            // fails, and is not also reported as closed. One that was, on a screen restored after the system destroyed
            // it in the background, closes, like an ad whose renderer dies while it shows.
            if (!impressionReported) {
                ad.onFailed(IllegalStateException("Ad has no view to display"))
                presentation = null
            }
            finish()
            return
        }

        val root = FrameLayout(this)
        setContentView(root)

        // The view belongs to the renderer and may still be attached to a previous container.
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(view)
        adView = view

        // The renderer closes the ad this way when its WebView can no longer draw.
        ad.closeRequester = { finish() }

        val closeButton = CloseButton(this, ad.closeButton).apply { onClick = { finish() } }
        root.addView(closeButton, closeButton.cornerLayoutParams())

        // The creative may run under the notch and the system bars. The close button may not.
        closeButton.insetFromSafeArea(marginDp = 0)

        // Rotation is locked and size changes are handled in place, but a theme, language, font
        // size or display density change still recreates this activity. The countdown carries on
        // rather than starting over.
        closeAvailableAtMs = savedInstanceState?.closeAvailableAtMs()
            ?: (SystemClock.elapsedRealtime() + ad.closeButton.delayMs)
        closeButton.becomeAvailableAt(closeAvailableAtMs) {
            holdBack.isEnabled = false
            // A display or playable ad has no completion to earn the reward by. It is earned when the user could
            // close the ad, so the DSP's close delay is also the reward delay. Only a rewarded placement acts on it.
            rewardEarned = true
            reportRewardIfEarned()
        }
    }

    /**
     * Reports the reward once it is earned, but never before the impression, which with no close delay would
     * otherwise reach the publisher first, and never for an ad already closing: its countdown keeps running until
     * the activity is destroyed. Called again by a recreated activity; [PresentedAd.onRewarded] pays only once. A
     * recreated activity whose close button is already available can report it from onCreate, after the impression
     * the activity before it reported.
     */
    private fun reportRewardIfEarned() {
        if (!rewardEarned || !impressionReported || isFinishing) return
        presentation?.onRewarded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCloseAvailableAtMs(closeAvailableAtMs)
        outState.putImpressionReported(impressionReported)
    }

    override fun onResume() {
        super.onResume()
        // Reported here rather than at startActivity, so it means the ad reached the screen.
        // Still not viewability-gated - that is BID-52.
        if (!impressionReported) {
            impressionReported = true
            presentation?.onImpression()
        }
        reportRewardIfEarned()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detach but never destroy: the WebView belongs to the renderer, which reuses it if the
        // ad is shown again and tears it down in its own destroy().
        (adView?.parent as? ViewGroup)?.removeView(adView)
        adView = null

        presentation?.closeRequester = null
        if (isFinishing) {
            presentation?.onClosed()
            PresentedAds.unregister(presentationKey)
        }
        presentation = null
    }
}
