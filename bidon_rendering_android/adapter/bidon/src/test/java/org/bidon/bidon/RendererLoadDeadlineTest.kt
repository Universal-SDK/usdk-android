package org.bidon.bidon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.bidon.rendering.AdRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RendererLoadDeadlineTest {

    private val scope = TestScope()
    private val reported = mutableListOf<String>()
    private var timeouts = 0

    private val listener = object : AdRenderer.Listener {
        override fun onLoaded() { reported += "loaded" }
        override fun onImpression() { reported += "impression" }
        override fun onClick() { reported += "click" }
        override fun onClose() { reported += "close" }
        override fun onReward() { reported += "reward" }
        override fun onError(error: Throwable) { reported += "error" }
    }

    private fun deadline() = RendererLoadDeadline(scope, TIMEOUT_MS, listener, onTimeout = { timeouts++ })

    private fun passTime(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun aLoadThatNeverEndsTimesOutOnceAtTheAdUnitsTimeout() {
        deadline()

        passTime(TIMEOUT_MS - 1)
        assertEquals(0, timeouts)

        passTime(1)
        passTime(10 * TIMEOUT_MS)
        assertEquals(1, timeouts)
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun aLoadInTimeReportsTheFillAndNoTimeout() {
        val deadline = deadline()

        passTime(TIMEOUT_MS - 1)
        deadline.onLoaded()
        passTime(10 * TIMEOUT_MS)

        assertEquals(0, timeouts)
        assertEquals(listOf("loaded"), reported)
    }

    @Test
    fun aFailureInTimeReportsTheFailureAndNoTimeout() {
        val deadline = deadline()

        deadline.onError(Exception())
        passTime(10 * TIMEOUT_MS)

        assertEquals(0, timeouts)
        assertEquals(listOf("error"), reported)
    }

    /** The response arrives just after the timeout has fired: the timeout was the one failure, and there is no fill. */
    @Test
    fun aLoadJustAfterTheTimeoutIsDropped() {
        val deadline = deadline()

        passTime(TIMEOUT_MS)
        deadline.onLoaded()
        deadline.onError(Exception())

        assertEquals(1, timeouts)
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun nothingTheRendererReportsAfterTheTimeoutIsPassedOn() {
        val deadline = deadline()

        passTime(TIMEOUT_MS)
        deadline.onImpression()
        deadline.onClick()
        deadline.onClose()
        deadline.onReward()

        assertEquals(emptyList<String>(), reported)
    }

    /** A video with no media file it can play fails only when it is shown, long after the load. */
    @Test
    fun anErrorAfterTheLoadIsPassedOn() {
        val deadline = deadline()

        deadline.onLoaded()
        passTime(10 * TIMEOUT_MS)
        deadline.onError(Exception())

        assertEquals(listOf("loaded", "error"), reported)
    }

    @Test
    fun theAdsEventsAfterTheLoadArePassedOn() {
        val deadline = deadline()

        deadline.onLoaded()
        deadline.onImpression()
        deadline.onClick()
        deadline.onReward()
        deadline.onClose()

        assertEquals(listOf("loaded", "impression", "click", "reward", "close"), reported)
    }

    @Test
    fun theLoadSettlesOnce() {
        val deadline = deadline()

        deadline.onError(Exception())
        deadline.onLoaded()
        deadline.onLoaded()

        assertEquals(listOf("error"), reported)
    }

    @Test
    fun aCancelledLoadNeitherTimesOutNorPassesAnythingOn() {
        val deadline = deadline()

        deadline.cancel()
        passTime(10 * TIMEOUT_MS)
        deadline.onLoaded()
        deadline.onError(Exception())
        deadline.onImpression()

        assertEquals(0, timeouts)
        assertEquals(emptyList<String>(), reported)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
