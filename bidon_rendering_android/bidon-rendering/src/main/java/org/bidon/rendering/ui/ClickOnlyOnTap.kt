package org.bidon.rendering.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * Lets the view's click listener fire for a tap only, not for a swipe or a long press.
 *
 * A plain click fires when the finger lifts anywhere inside the view. On a view that fills the
 * screen, a swipe across the ad therefore counted as a click and opened the advertiser's page.
 * A tap still ends in [View.performClick], so accessibility services click the view as before.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun View.clickOnlyOnTap() {
    val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean = performClick()
    })
    setOnTouchListener { _, event -> taps.onTouchEvent(event) }
}
