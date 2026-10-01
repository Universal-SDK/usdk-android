package org.bidon.rendering.mraid.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.ui.CloseButton
import org.bidon.rendering.ui.closeAvailableAtMs
import org.bidon.rendering.ui.impressionReported
import org.bidon.rendering.ui.insetFromSafeArea
import org.bidon.rendering.ui.putCloseAvailableAtMs
import org.bidon.rendering.ui.putImpressionReported

/**
 * Hosts a fullscreen MRAID creative.
 *
 * Like the HTML one, this adopts the WebView the renderer already loaded rather than building a
 * second one with its own controller and bridge. That is what keeps the creative's MRAID state,
 * its click handling and its close command connected to the renderer that owns it.
 */
internal class MraidAdActivity : AppCompatActivity() {

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
            finish()
            return
        }
        presentation = ad
        impressionReported = savedInstanceState?.impressionReported() ?: false

        requestedOrientation = ad.lockedOrientation

        val view = ad.contentView ?: run {
            // Its WebView is gone, for instance because its renderer process died. An ad that was never on screen
            // fails, and is not also reported as closed. One that was, on a screen restored after the system destroyed
            // it in the background, closes, like an ad whose renderer dies while it shows.
            if (!impressionReported) {
                ad.onFailed(IllegalStateException("MRAID ad has no view to display"))
                presentation = null
            }
            finish()
            return
        }

        // mraid.close() from inside the creative dismisses this activity.
        ad.closeRequester = { finish() }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(view)
        adView = view

        val closeButton = CloseButton(this, ad.closeButton).apply { onClick = { finish() } }
        root.addView(closeButton, closeButton.cornerLayoutParams())

        // Was missing entirely here: the close button could sit under a notch or gesture bar.
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
        if (!impressionReported) {
            impressionReported = true
            presentation?.onImpression()
        }
        reportRewardIfEarned()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detach only. The WebView, its controller and its bridge belong to the renderer.
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
