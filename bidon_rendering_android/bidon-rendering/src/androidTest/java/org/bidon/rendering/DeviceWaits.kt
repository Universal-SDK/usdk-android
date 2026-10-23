package org.bidon.rendering

import android.app.Instrumentation
import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import org.junit.Assert.assertTrue

/** Waits until [condition], read on the main thread, holds; fails with [failure] when it does not within 10 s. */
internal fun Instrumentation.waitUntil(failure: String, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
    while (true) {
        var met = false
        runOnMainSync { met = condition() }
        if (met) return
        assertTrue(failure, SystemClock.uptimeMillis() < deadline)
        SystemClock.sleep(100)
    }
}

/**
 * Runs [block] with the device turned on its side by the system, as a user turning it does, which recreates the
 * resumed activity. The device is turned back and unfrozen afterwards, and waited for, so the next test starts upright
 * without hiding why this one failed.
 */
internal fun Instrumentation.whileTheDeviceIsRotated(block: () -> Unit) {
    val rotation = displayRotation()
    try {
        uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        block()
    } finally {
        uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (displayRotation() != rotation && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }
}

private fun Instrumentation.displayRotation(): Int =
    targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

private const val WAIT_MILLIS = 10_000L
