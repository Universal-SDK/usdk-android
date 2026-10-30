package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The codes a show can fail with from a renderer or a screen: the ones the spec gives a show stage. */
internal val SHOW_CODES = listOf(
    ErrorCode.NO_PLAYABLE_MEDIA,
    ErrorCode.PLAYER_ERROR,
    ErrorCode.WEBVIEW_GONE_AT_SHOW,
    ErrorCode.AD_SCREEN_NOT_STARTED,
    ErrorCode.AD_SCREEN_NO_VIEW,
    ErrorCode.PLAYER_NETWORK,
    ErrorCode.PLAYER_DECODE,
    ErrorCode.SHOW_NOT_READY,
    ErrorCode.SHOW_TWICE,
    ErrorCode.SHOWN_FROM_OTHER_PROCESS,
)

/**
 * BID-140 AC 6: whatever order the calls come in, and from however many threads, a show request is followed by at
 * most one terminal (the impression or `ad_show_failed`), exactly one when the ad is closed, and a show that passed
 * the ready check by no `ad_load_failed`.
 *
 * Terminals are counted per show request. The impression, which an ad has once, and each show failure close one open
 * request; a failure or an impression with no open request closes none; closing the ad fails a request still open. A
 * request refused at the ready check never starts, so it does not hide a later load failure.
 */
class AdEventsShowPropertyTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!

    private enum class Op {
        LOAD_SUCCEEDED, LOAD_FAILED, SHOW_REQUESTED, SHOW_STARTED, RENDERED, SHOW_FAILED, PROCESS_GONE, CLOSED,
    }

    private data class Step(val op: Op, val code: ErrorCode)

    private val step: Arb<Step> = Arb.bind(Arb.enum<Op>(), Arb.element(SHOW_CODES), ::Step)

    private fun adEvents(telemetry: Telemetry, renderer: Renderer) =
        AdEvents(telemetry, session, "a", AdType.INTERSTITIAL, null, renderer, declaredType = null, { 0L }, { 0L }, { 0L })

    private fun AdEvents.perform(step: Step) {
        when (step.op) {
            Op.LOAD_SUCCEEDED -> loadSucceeded()
            Op.LOAD_FAILED -> loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            Op.SHOW_REQUESTED -> showRequested()
            Op.SHOW_STARTED -> showStarted()
            Op.RENDERED -> rendered()
            Op.SHOW_FAILED -> showFailed(step.code)
            Op.PROCESS_GONE -> processGone(ProcessExit(didCrash = false, priorityAtExit = null))
            Op.CLOSED -> closed()
        }
    }

    /** What the events must emit, by the rules of the class, for the steps applied one after another. */
    private class Model {
        var open = 0
            private set
        private var started = false
        private var impressed = false
        private var loadFailureUsed = false
        private var deathUsed = false

        /** The label of the event [step] emits, or `null` when it emits none. */
        fun next(step: Step): String? {
            when (step.op) {
                Op.LOAD_SUCCEEDED -> Unit
                Op.LOAD_FAILED -> if (!started && !loadFailureUsed) {
                    loadFailureUsed = true
                    return "ad_load_failed"
                }
                Op.SHOW_REQUESTED -> {
                    open++
                    return "ad_show_requested"
                }
                Op.SHOW_STARTED -> started = true
                Op.RENDERED -> if (!impressed) {
                    impressed = true
                    if (open > 0) open--
                    return "ad_impression"
                }
                Op.SHOW_FAILED -> if (open > 0) {
                    open--
                    return "ad_show_failed:${step.code.number}"
                }
                Op.PROCESS_GONE -> {
                    loadFailureUsed = true
                    if (!deathUsed) {
                        deathUsed = true
                        return "render_crashed"
                    }
                }
                Op.CLOSED -> {
                    deathUsed = true
                    if (open > 0) {
                        open--
                        return "ad_show_failed:7006"
                    }
                }
            }
            return null
        }
    }

    private fun expected(steps: List<Step>): List<String> = Model().let { model -> steps.mapNotNull(model::next) }

    /**
     * [steps] with every show request that would open a second show at once turned into a load success, so that a
     * close, which fails one open show, never meets two.
     */
    private fun oneShowOpenAtATime(steps: List<Step>): List<Step> {
        val model = Model()
        return steps.map { step ->
            val allowed = if (step.op == Op.SHOW_REQUESTED && model.open > 0) step.copy(op = Op.LOAD_SUCCEEDED) else step
            allowed.also { model.next(it) }
        }
    }

    private fun label(emitted: RecordingTelemetry.Emitted): String {
        if (emitted.name != "ad_show_failed") return emitted.name
        return "ad_show_failed:" + emitted.json().get("error_code")
    }

    @Test
    fun `any sequence of calls emits what the terminal rules say, in order`() {
        runBlocking {
            checkAll(Arb.list(step, 0..25).map(::oneShowOpenAtATime), Arb.enum<Renderer>()) { steps, renderer ->
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, renderer)

                steps.forEach { events.perform(it) }

                assertEquals(steps.toString(), expected(steps), telemetry.emitted.map(::label))
            }
        }
    }

    @Test
    fun `a sequence emits the same events whether or not telemetry keeps them`() {
        runBlocking {
            checkAll(Arb.list(step, 0..25).map(::oneShowOpenAtATime), Arb.enum<Renderer>()) { steps, renderer ->
                val kept = RecordingTelemetry()
                val dropped = RecordingTelemetry().apply { rate = null }
                val keptEvents = adEvents(kept, renderer)
                val droppedEvents = adEvents(dropped, renderer)

                steps.forEach { keptEvents.perform(it) }
                steps.forEach { droppedEvents.perform(it) }

                assertEquals(steps.toString(), kept.emitted.map { it.name }, dropped.emitted.map { it.name })
                assertEquals(0, dropped.captures)
            }
        }
    }

    @Test
    fun `a close that fails a show says the stage the creative had reached`() {
        runBlocking {
            checkAll(Arb.list(step, 0..25).map(::oneShowOpenAtATime), Arb.enum<Renderer>()) { steps, renderer ->
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, renderer)

                steps.forEach { events.perform(it) }

                val closeStage = if (renderer == Renderer.VAST) "playback_start" else "view_attach"
                telemetry.emitted.filter { it.name == "ad_show_failed" }.map { it.json() }
                    .filter { it.get("error_code") == "7006" }
                    .forEach { assertEquals(steps.toString(), closeStage, it.getString("failed_stage")) }
            }
        }
    }

    @Test
    fun `on any number of threads after a started show, no load failure, at most one terminal per request, one when closed`() {
        val pool = Executors.newFixedThreadPool(MAX_THREADS)
        try {
            runBlocking {
                checkAll(ITERATIONS, Arb.list(step, 1..30), Arb.int(1..MAX_THREADS), Arb.enum<Renderer>()) { steps, threads, renderer ->
                    val telemetry = RecordingTelemetry()
                    val events = adEvents(telemetry, renderer)
                    events.showRequested()
                    events.showStarted()

                    val ready = CountDownLatch(threads)
                    val go = CountDownLatch(1)
                    val futures = steps.chunkedInto(threads).map { chunk ->
                        pool.submit {
                            ready.countDown()
                            go.await()
                            chunk.forEach { events.perform(it) }
                        }
                    }
                    assertTrue("threads ready", ready.await(5, TimeUnit.SECONDS))
                    go.countDown()
                    futures.forEach { it.get(10, TimeUnit.SECONDS) }

                    val ops = steps.map { it.op }
                    val requests = 1 + ops.count { it == Op.SHOW_REQUESTED }
                    val names = telemetry.emitted.map { it.name }
                    val message = "$steps on $threads threads: $names"
                    assertEquals(message, 0, names.count { it == "ad_load_failed" })
                    assertEquals(message, requests, names.count { it == "ad_show_requested" })
                    val failures = names.count { it == "ad_show_failed" }
                    assertTrue(message, failures <= requests)
                    assertTrue(message, failures <= ops.count { it == Op.SHOW_FAILED || it == Op.CLOSED })
                    assertTrue(message, names.count { it == "ad_impression" } <= 1)
                    assertTrue(message, names.count { it == "render_crashed" } <= 1)
                    if (requests == 1) {
                        assertTrue(message, failures <= 1)
                        if (Op.RENDERED !in ops && (Op.CLOSED in ops || Op.SHOW_FAILED in ops)) {
                            assertEquals(message, 1, failures)
                        }
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `started shows racing a load failure on many threads each emit their request, and the load failure at most once`() {
        val pool = Executors.newFixedThreadPool(MAX_THREADS)
        try {
            runBlocking {
                checkAll(ITERATIONS, Arb.int(1..MAX_THREADS)) { threads ->
                    val telemetry = RecordingTelemetry()
                    val events = adEvents(telemetry, Renderer.HTML)
                    val ready = CountDownLatch(threads)
                    val go = CountDownLatch(1)
                    val futures = List(threads) { index ->
                        pool.submit {
                            ready.countDown()
                            go.await()
                            if (index % 2 == 0) {
                                events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
                            } else {
                                events.showRequested()
                                events.showStarted()
                            }
                        }
                    }
                    assertTrue("threads ready", ready.await(5, TimeUnit.SECONDS))
                    go.countDown()
                    futures.forEach { it.get(10, TimeUnit.SECONDS) }

                    val names = telemetry.emitted.map { it.name }
                    assertTrue(names.toString(), names.count { it == "ad_load_failed" } <= 1)
                    assertEquals(names.toString(), threads / 2, names.count { it == "ad_show_requested" })
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun <T> List<T>.chunkedInto(parts: Int): List<List<T>> = List(parts) { part -> filterIndexed { i, _ -> i % parts == part } }

    private companion object {
        const val MAX_THREADS = 4
        const val ITERATIONS = 300
    }
}
