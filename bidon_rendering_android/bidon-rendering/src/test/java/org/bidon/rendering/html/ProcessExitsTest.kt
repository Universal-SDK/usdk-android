package org.bidon.rendering.html

import android.annotation.SuppressLint
import android.webkit.WebView
import org.bidon.rendering.telemetry.ProcessExit
import org.bidon.rendering.telemetry.RendererPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the platform's death detail becomes a [ProcessExit]. `RenderProcessGoneDetail` cannot be built on the JVM, so the
 * mapping is tested on the two raw values it reads, `didCrash()` and `rendererPriorityAtExit()`; the function that takes
 * the detail only reads them.
 */
class ProcessExitsTest {

    @Test
    fun `the three priorities the platform defines map to waived, bound and important`() {
        assertEquals(RendererPriority.WAIVED, processExitOf(false, WebView.RENDERER_PRIORITY_WAIVED).priorityAtExit)
        assertEquals(RendererPriority.BOUND, processExitOf(false, WebView.RENDERER_PRIORITY_BOUND).priorityAtExit)
        assertEquals(RendererPriority.IMPORTANT, processExitOf(false, WebView.RENDERER_PRIORITY_IMPORTANT).priorityAtExit)
    }

    @Test
    fun `the priority constants are the numbers the mapping is checked against`() {
        assertEquals(0, WebView.RENDERER_PRIORITY_WAIVED)
        assertEquals(1, WebView.RENDERER_PRIORITY_BOUND)
        assertEquals(2, WebView.RENDERER_PRIORITY_IMPORTANT)
    }

    @Test
    fun `the wire names of the priorities are lower case`() {
        assertEquals(
            listOf("waived", "bound", "important"),
            listOf(RendererPriority.WAIVED, RendererPriority.BOUND, RendererPriority.IMPORTANT).map { it.wireName },
        )
        assertEquals(3, RendererPriority.entries.size)
    }

    @Test
    fun `an unknown priority is left out, never a number or a guess`() {
        listOf(-1, 3, 4, 100, Int.MAX_VALUE, Int.MIN_VALUE).forEach { unknown ->
            assertEquals("$unknown", null, processExitOf(true, unknown).priorityAtExit)
        }
    }

    @Test
    fun `an unknown priority does not hide the crash flag`() {
        assertEquals(ProcessExit(didCrash = true, priorityAtExit = null), processExitOf(true, 7))
        assertEquals(ProcessExit(didCrash = false, priorityAtExit = null), processExitOf(false, 7))
    }

    @Test
    fun `a crash and a system kill keep their flag, with or without a priority`() {
        assertEquals(
            ProcessExit(didCrash = true, priorityAtExit = RendererPriority.IMPORTANT),
            processExitOf(true, WebView.RENDERER_PRIORITY_IMPORTANT),
        )
        assertEquals(
            ProcessExit(didCrash = false, priorityAtExit = RendererPriority.WAIVED),
            processExitOf(false, WebView.RENDERER_PRIORITY_WAIVED),
        )
        assertEquals(ProcessExit(didCrash = true, priorityAtExit = null), processExitOf(true, null))
        assertEquals(ProcessExit(didCrash = false, priorityAtExit = null), processExitOf(false, null))
    }

    @Test
    fun `no detail leaves both out, so an unknown cause is never counted as a system kill`() {
        assertEquals(ProcessExit(didCrash = null, priorityAtExit = null), processExitOf(null, null))
    }

    @Test
    fun `a priority without a crash flag keeps the priority only`() {
        assertEquals(
            ProcessExit(didCrash = null, priorityAtExit = RendererPriority.BOUND),
            processExitOf(null, WebView.RENDERER_PRIORITY_BOUND),
        )
    }

    // A JVM test has no API level to check.
    @Test
    @SuppressLint("NewApi")
    fun `a missing detail is the same as no raw values`() {
        assertEquals(ProcessExit(didCrash = null, priorityAtExit = null), processExitOf(detail = null))
    }
}
