package org.bidon.rendering

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.telemetry.CrashCapture
import org.bidon.rendering.telemetry.Telemetry
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Crash capture follows the telemetry switch in the host app's manifest: an app that has not switched telemetry on
 * keeps its crash handler exactly as it installed it.
 */
@RunWith(AndroidJUnit4::class)
class CrashCaptureSwitchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val hostReporter = Thread.UncaughtExceptionHandler { _, _ -> }

    @After
    fun restoreDefaultHandler() = Thread.setDefaultUncaughtExceptionHandler(original)

    @Test
    fun withTelemetryOffTheHostsHandlerIsLeftAlone() {
        Thread.setDefaultUncaughtExceptionHandler(hostReporter)

        val telemetry = RendererTelemetry.start(WithoutTelemetryFlag(context))

        assertSame(Telemetry.Disabled, telemetry)
        assertSame(hostReporter, Thread.getDefaultUncaughtExceptionHandler())
    }

    /** Without this, the test above could pass because crash capture was never installed at all. */
    @Test
    fun withTelemetryOnTheHostsHandlerIsWrapped() {
        Thread.setDefaultUncaughtExceptionHandler(hostReporter)

        RendererTelemetry.start(context)

        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashCapture)
    }

    /**
     * The test app with the manifest of the system package, which has no telemetry flag. The test app's own manifest
     * switches telemetry on.
     */
    private class WithoutTelemetryFlag(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "android"
    }
}
