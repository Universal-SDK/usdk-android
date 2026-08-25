package org.bidon.rendering.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
    private var closeButton: CloseButton? = null

    private var impressionReported = false
    private var closeAllowed = false

    private val allowCloseRunnable = Runnable {
        closeAllowed = true
        closeButton?.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        presentationKey = intent.getStringExtra(PresentedAds.EXTRA_KEY)
        val ad = PresentedAds.get(presentationKey) ?: run {
            // The renderer was destroyed before we got here. Nothing to show and nobody to tell.
            finish()
            return
        }
        presentation = ad

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

        val margin = (16 * resources.displayMetrics.density).toInt()

        closeButton = CloseButton(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = margin
                topMargin = margin
            }
            onClick = { finish() }
        }
        root.addView(closeButton!!)

        val delayMs = ad.closeDelayMs
        if (delayMs > 0) {
            closeButton?.postDelayed(allowCloseRunnable, delayMs)
        } else {
            allowCloseRunnable.run()
        }

        ViewCompat.setOnApplyWindowInsetsListener(closeButton!!) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = safeArea.top + margin
                rightMargin = safeArea.right + margin
            }
            insets
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

        closeButton?.removeCallbacks(allowCloseRunnable)

        // Detach but never destroy: the WebView belongs to the renderer, which reuses it if the
        // ad is shown again and tears it down in its own destroy().
        (adView?.parent as? ViewGroup)?.removeView(adView)
        adView = null
        closeButton = null

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
