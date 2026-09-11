package org.bidon.rendering.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
class FullscreenAdActivity : AppCompatActivity() {

    private var presentationKey: String? = null
    private var presentation: PresentedAd? = null
    private var adView: View? = null

    private var impressionReported = false
    private var closeAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        AdWindow.goFullscreen(this)

        presentationKey = intent.getStringExtra(PresentedAds.EXTRA_KEY)
        val ad = PresentedAds.get(presentationKey) ?: run {
            // The renderer was destroyed before we got here. Nothing to show and nobody to tell.
            finish()
            return
        }
        presentation = ad

        // Locked so the ad cannot rotate while it is up, restarting the creative and moving the
        // close button. Inherited from the publisher, resolved when the ad was shown.
        requestedOrientation = ad.lockedOrientation

        val view = ad.contentView ?: run {
            ad.onFailed(IllegalStateException("Ad has no view to display"))
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

        val closeButton = CloseButton(this, ad.closeButton).apply { onClick = { finish() } }
        root.addView(closeButton, closeButton.cornerLayoutParams())

        // The creative may run under the notch and the system bars. The close button may not.
        closeButton.insetFromSafeArea(ad.closeButton.paddingDp)

        closeButton.becomeAvailableAt(SystemClock.elapsedRealtime() + ad.closeButton.delayMs) {
            closeAllowed = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Reported here rather than at startActivity, so it means the ad reached the screen.
        // Still not viewability-gated - that is BID-52.
        if (!impressionReported) {
            impressionReported = true
            presentation?.onImpression()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detach but never destroy: the WebView belongs to the renderer, which reuses it if the
        // ad is shown again and tears it down in its own destroy().
        (adView?.parent as? ViewGroup)?.removeView(adView)
        adView = null

        if (isFinishing) {
            presentation?.onClosed()
            PresentedAds.unregister(presentationKey)
        }
        presentation = null
    }

    override fun onBackPressed() {
        // Back is held until the close button is available, so the ad cannot be dismissed by an
        // accidental press before it has been seen.
        if (closeAllowed) {
            super.onBackPressed()
        }
    }
}
