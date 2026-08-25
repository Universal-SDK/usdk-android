package org.bidon.rendering.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * The window rules every fullscreen ad follows: which way up it sits, and keeping its controls
 * out from under the notch.
 */
internal object AdWindow {

    /**
     * Android's minimum touch target, for any control the user has to hit. The close
     * button used to be 120px in one activity and 70px in another, neither converted for
     * density, so both shrank as the screen got denser.
     */
    const val TOUCH_TARGET_DP = 48

    /** Gap between a control and the edge of the safe area. */
    const val CONTROL_MARGIN_DP = 16

    /**
     * The orientation an ad should lock to, taken from the app that is showing it.
     *
     * An ad is a short interruption, not content the user navigates. Rotating mid-ad reflows a
     * creative authored for one shape, restarts video, and moves the close button out from under
     * the user's thumb. So it locks - but to the publisher's own policy rather than forcing
     * portrait, which would break a landscape game.
     *
     * When the publisher allows both, LOCKED freezes at whatever the screen is the moment the ad
     * opens.
     */
    fun orientationInheritedFrom(parent: Activity): Int =
        lockedFor(parent.requestedOrientation)

    /** The pure half of the rule above, split out so it can be tested without an Activity. */
    fun lockedFor(publisherOrientation: Int): Int = when (publisherOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT ->
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ->
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        else -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    /** Hides the system bars and lets the creative draw edge to edge behind them. */
    fun goFullscreen(activity: Activity) {
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

/** Density-independent size in pixels, for views built in code rather than XML. */
internal fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/**
 * Keeps a control clear of the status bar, gesture bar and any display cutout.
 *
 * The creative is allowed to run under them - full-bleed is the point of a fullscreen ad - but a
 * button the user has to hit is not.
 */
internal fun View.insetFromSafeArea(marginDp: Int) {
    val base = dp(marginDp)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = safe.top + base
            bottomMargin = safe.bottom + base
            leftMargin = safe.left + base
            rightMargin = safe.right + base
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
