package org.bidon.rendering.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A full-screen ad view must open the advertiser's page for a tap and for nothing else a finger
 * does on it, while staying clickable for accessibility services.
 *
 * Instrumented, with the view on a real screen: the tap is recognised by the platform's
 * GestureDetector, and the plain click this guards against is posted to the window's message
 * queue, so a view that is not attached would never show it.
 */
@RunWith(AndroidJUnit4::class)
class ClickOnlyOnTapTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * Shows a view that counts its clicks, runs [touches] on it, and returns the count once the
     * main thread is idle, so a click the platform posts for later is counted too.
     */
    private fun clicks(touches: View.() -> Unit): Int {
        var clicks = 0
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var view: View
            scenario.onActivity { activity ->
                view = View(activity).apply {
                    setOnClickListener { clicks++ }
                    clickOnlyOnTap()
                }
                activity.setContentView(view)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { view.touches() }
            instrumentation.waitForIdleSync()
        }
        return clicks
    }

    /** Sends a finger down at the first point, through the rest, and up at the last, as fractions of the view. */
    private fun View.touch(vararg points: Pair<Float, Float>) {
        val downTime = SystemClock.uptimeMillis()
        points.forEachIndexed { index, (x, y) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action, x * width, y * height, 0)
            dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun aTapClicks() {
        assertEquals(1, clicks { touch(0.5f to 0.5f, 0.5f to 0.5f) })
    }

    @Test
    fun aFingerThatBarelyMovesStillClicks() {
        assertEquals(1, clicks { touch(0.5f to 0.5f, 0.502f to 0.501f, 0.502f to 0.501f) })
    }

    @Test
    fun aSwipeAcrossTheViewDoesNotClick() {
        assertEquals(0, clicks { touch(0.2f to 0.5f, 0.5f to 0.5f, 0.8f to 0.5f, 0.8f to 0.5f) })
    }

    @Test
    fun aVerticalScrollDoesNotClick() {
        assertEquals(0, clicks { touch(0.5f to 0.8f, 0.5f to 0.6f, 0.5f to 0.35f, 0.5f to 0.35f) })
    }

    @Test
    fun accessibilityServicesCanStillClick() {
        assertEquals(1, clicks { performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) })
    }
}
