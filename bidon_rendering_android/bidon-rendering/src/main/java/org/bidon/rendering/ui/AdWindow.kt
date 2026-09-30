package org.bidon.rendering.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
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

    /**
     * Hides the status bar and lets the creative draw edge to edge, behind a navigation bar the
     * theme makes transparent.
     *
     * The navigation bar stays shown on purpose. While it is hidden Android turns the back gesture
     * off, so on a phone with gesture navigation the swipe reached the ad as a tap: Back did
     * nothing and the advertiser's page opened instead.
     */
    fun goFullscreen(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/** Density-independent size in pixels, for views built in code rather than XML. */
internal fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/**
 * Keeps a control clear of the status bar, gesture bar and any display cutout.
 *
 * The creative is allowed to run under all of them - full-bleed is the point of a fullscreen ad -
 * but a button the user has to hit is not.
 */
internal fun View.insetFromSafeArea(marginDp: Int) {
    val base = dp(marginDp)

    fun apply(insets: WindowInsetsCompat) {
        val safe = safeArea(insets)
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = safe.top + base
            bottomMargin = safe.bottom + base
            leftMargin = safe.left + base
            rightMargin = safe.right + base
        }
    }

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        apply(insets)
        insets
    }

    // The listener alone is not enough. A view that is not attached yet gets no dispatch, and
    // requesting one on it does nothing, so the first placement would never happen.
    doOnAttach { view ->
        ViewCompat.getRootWindowInsets(view)?.let(::apply)
        ViewCompat.requestApplyInsets(view)
    }
}

/**
 * The area a control must stay out of, on each edge.
 *
 * Two details matter and both were wrong first time round.
 *
 * The bars are asked for their size *ignoring visibility*. An ad hides them for immersive
 * fullscreen, and a hidden bar reports an inset of zero - so asking the ordinary way put controls
 * right against the top of the screen, exactly where the status bar had been.
 *
 * Bars and cutout are compared per edge rather than combined, because they overlap: on most phones
 * the notch sits inside the status bar strip, so adding them would push controls twice as far down
 * as they need to go.
 */
private fun safeArea(insets: WindowInsetsCompat): Insets {
    val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
    val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
    return Insets.of(
        maxOf(bars.left, cutout.left),
        maxOf(bars.top, cutout.top),
        maxOf(bars.right, cutout.right),
        maxOf(bars.bottom, cutout.bottom)
    )
}
