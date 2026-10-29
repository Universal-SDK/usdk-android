package org.bidon.rendering.html

import android.os.Build
import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The probe's own rules, driven by a WebView whose answers the test controls. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class ContentProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val webViews = mutableListOf<WebView>()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webViews.forEach(WebView::destroy) }
    }

    /** Answers each script with whatever [answer] returns, or keeps the callback when it returns null. */
    private inner class ScriptedWebView(private val answer: () -> String?) : WebView(context) {
        val calls = AtomicInteger()
        val held = CopyOnWriteArrayList<ValueCallback<String>>()

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            calls.incrementAndGet()
            val reply = answer()
            if (reply == null) resultCallback?.let(held::add) else resultCallback?.onReceiveValue(reply)
        }
    }

    private class Results {
        val values = CopyOnWriteArrayList<Boolean>()
        var at = 0L
        fun record(hasContent: Boolean) {
            at = SystemClock.elapsedRealtime()
            values += hasContent
        }
    }

    private fun webView(answer: () -> String?) = onMain { ScriptedWebView(answer) }.also { webViews += it }

    private fun probe(webView: WebView, results: Results) = onMain { ContentProbe(webView, results::record) }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun anAnswerOfNullCountsAsContent() {
        val results = Results()
        val probe = probe(webView { "null" }, results)

        onMain { probe.start() }

        waitUntil("the probe never answered") { results.values.isNotEmpty() }
        assertEquals(listOf(true), results.values.toList())
    }

    @Test
    fun aPageThatStaysEmptyIsReportedBlankOnceTheWaitEnds() {
        val results = Results()
        val probe = probe(webView { "false" }, results)

        val start = SystemClock.elapsedRealtime()
        onMain { probe.start() }
        SystemClock.sleep(ContentProbe.EMPTY_PAGE_WAIT_MILLIS - EARLY_MARGIN_MILLIS)
        assertTrue("reported before the wait ended", results.values.isEmpty())

        waitUntil("the probe never answered") { results.values.isNotEmpty() }
        assertEquals(listOf(false), results.values.toList())
        val took = results.at - start
        assertTrue("took $took ms", took >= ContentProbe.EMPTY_PAGE_WAIT_MILLIS - EARLY_MARGIN_MILLIS)
    }

    /** A page that answers after the watchdog is alive: any answer, a "no" included, finishes it as content, and not before. */
    @Test
    fun anAnswerAfterTheWatchdogFinishesAsContent() {
        val results = Results()
        val view = webView { null }
        val probe = probe(view, results)

        onMain { probe.start() }
        SystemClock.sleep(WATCHDOG_MILLIS + LATE_MARGIN_MILLIS)
        assertTrue("reported before the page answered", results.values.isEmpty())

        val answeredAt = SystemClock.elapsedRealtime()
        onMain { view.held.single().onReceiveValue("false") }

        waitUntil("the probe never answered") { results.values.isNotEmpty() }
        SystemClock.sleep(IDLE_MILLIS)
        assertEquals(listOf(true), results.values.toList())
        assertTrue("reported before the page answered", results.at >= answeredAt)
        val lag = results.at - answeredAt
        assertTrue("reported $lag ms after the page answered", lag < IDLE_MILLIS)
    }

    /** A page that stays silent past the watchdog counts as content once the limit runs out, and not before. */
    @Test
    fun aPageThatStaysSilentCountsAsContentOnceTheLimitRunsOut() {
        val results = Results()
        val probe = probe(webView { null }, results)

        val start = SystemClock.elapsedRealtime()
        onMain { probe.start() }
        SystemClock.sleep(ContentProbe.SILENT_PAGE_LIMIT_MILLIS - EARLY_MARGIN_MILLIS)
        assertTrue("reported before the limit", results.values.isEmpty())

        waitUntil("the probe never answered") { results.values.isNotEmpty() }
        SystemClock.sleep(IDLE_MILLIS)
        assertEquals(listOf(true), results.values.toList())
        val took = results.at - start
        assertTrue("took $took ms", took >= ContentProbe.SILENT_PAGE_LIMIT_MILLIS - EARLY_MARGIN_MILLIS)
    }

    /** The renderer cancels the probe when the render process dies: a silent page then reports nothing at all. */
    @Test
    fun cancelAfterTheWatchdogReportsNothing() {
        val results = Results()
        val view = webView { null }
        val probe = probe(view, results)

        onMain { probe.start() }
        SystemClock.sleep(WATCHDOG_MILLIS + LATE_MARGIN_MILLIS)
        onMain { probe.cancel() }
        onMain { view.held.single().onReceiveValue("true") }

        SystemClock.sleep(ContentProbe.SILENT_PAGE_LIMIT_MILLIS - WATCHDOG_MILLIS + IDLE_MILLIS)
        assertTrue("reported after cancel", results.values.isEmpty())
    }

    @Test
    fun anAnswerThatArrivesAfterCancelIsIgnored() {
        val results = Results()
        val view = webView { null }
        val probe = probe(view, results)

        onMain { probe.start() }
        assertEquals(1, view.held.size)
        onMain { probe.cancel() }
        onMain { view.held.single().onReceiveValue("true") }

        SystemClock.sleep(IDLE_MILLIS)
        assertTrue("reported after cancel", results.values.isEmpty())
    }

    @Test
    fun cancelStopsThePolling() {
        val results = Results()
        val view = webView { "false" }
        val probe = probe(view, results)

        onMain { probe.start() }
        onMain { probe.cancel() }
        val callsAtCancel = view.calls.get()

        SystemClock.sleep(IDLE_MILLIS)
        assertEquals("evaluated after cancel", callsAtCancel, view.calls.get())
        assertTrue("reported after cancel", results.values.isEmpty())
    }

    @Test
    fun theResultIsReportedOnceHoweverManyAnswersCome() {
        val results = Results()
        val view = webView { null }
        val probe = probe(view, results)

        onMain { probe.start() }
        val first = view.held.single()
        onMain {
            first.onReceiveValue("true")
            first.onReceiveValue("true")
            first.onReceiveValue("false")
        }

        SystemClock.sleep(IDLE_MILLIS)
        assertEquals(listOf(true), results.values.toList())
        assertEquals("evaluated after the result", 1, view.calls.get())
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ContentProbe.SILENT_PAGE_LIMIT_MILLIS + 5_000
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(20)
        }
    }

    private companion object {
        /** Slack for the first poll running a little before the clock reads a full window. */
        const val EARLY_MARGIN_MILLIS = 200L

        /** More than three poll periods. */
        const val IDLE_MILLIS = 500L

        /** When a page that has not answered starts to count as content: the probe's 2 s wait plus its 0.5 s grace. */
        const val WATCHDOG_MILLIS = 2_500L

        /** Slack for the watchdog running late; the answer still comes well before the 12 s limit. */
        const val LATE_MARGIN_MILLIS = 1_000L
    }
}
