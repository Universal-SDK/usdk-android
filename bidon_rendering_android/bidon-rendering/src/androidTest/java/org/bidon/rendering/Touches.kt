package org.bidon.rendering

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** A tap in the middle of the view, dispatched to it as a finger down and up. Call on the main thread. */
fun View.tap() = touch(width / 2f to height / 2f, width / 2f to height / 2f)

/** A finger dragged from the top of the view to its bottom. Call on the main thread. */
fun View.swipe() = touch(width / 2f to height * 0.1f, width / 2f to height * 0.5f, width / 2f to height * 0.9f)

/** Puts a finger down at the first point, moves it through the others, and lifts it at the last. */
private fun View.touch(vararg points: Pair<Float, Float>) {
    val down = SystemClock.uptimeMillis()
    points.forEachIndexed { i, (x, y) ->
        val action = when (i) {
            0 -> MotionEvent.ACTION_DOWN
            points.lastIndex -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        val event = MotionEvent.obtain(down, down + i * 50L, action, x, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }
}
