package org.bidon.rendering.mraid.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.ui.CloseButton

/**
 * Hosts a fullscreen MRAID creative.
 *
 * Like the HTML one, this adopts the WebView the renderer already loaded rather than building a
 * second one with its own controller and bridge. That is what keeps the creative's MRAID state,
 * its click handling and its close command connected to the renderer that owns it.
 */
class MraidAdActivity : AppCompatActivity() {

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
            finish()
            return
        }
        presentation = ad

        val view = ad.contentView ?: run {
            ad.onFailed(IllegalStateException("MRAID ad has no view to display"))
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

        val margin = (16 * resources.displayMetrics.density).toInt()

        closeButton = CloseButton(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(70, 70).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = margin
                marginEnd = margin
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
    }

    override fun onResume() {
        super.onResume()
        if (!impressionReported) {
            impressionReported = true
            presentation?.onImpression()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        closeButton?.removeCallbacks(allowCloseRunnable)

        // Detach only. The WebView, its controller and its bridge belong to the renderer.
        (adView?.parent as? ViewGroup)?.removeView(adView)
        adView = null
        closeButton = null

        presentation?.closeRequester = null
        if (isFinishing) {
            presentation?.onClosed()
            PresentedAds.unregister(presentationKey)
        }
        presentation = null
    }

    override fun onBackPressed() {
        if (closeAllowed) {
            super.onBackPressed()
        }
    }
}
