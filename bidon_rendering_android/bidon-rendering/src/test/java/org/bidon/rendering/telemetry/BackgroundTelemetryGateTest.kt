package org.bidon.rendering.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [Telemetry.emit] with a capture lambda asks the gate first (D-031): the lambda runs on the caller's thread only for
 * an event the gate lets through. [BackgroundTelemetry.configure] carries the kill switch to the telemetry thread,
 * which then neither sends nor wakes until it is switched back on.
 *
 * The session is `abc`, whose position is about 0.728: kept at 0.8, dropped at 0.5.
 */
class BackgroundTelemetryGateTest {

    /** A call that blocks when it must not fails its test instead of hanging the run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val store = RecordingStore()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(MonotonicClock { 0L }))

    /** Released at the end of every test, so no telemetry thread stays blocked inside the store after its test. */
    private val release = CountDownLatch(1)

    @After
    fun releaseStore() = release.countDown()

    /** The thread the last [start] created, captured from inside the store's opening, which runs on it. */
    private val thread = AtomicReference<Thread>()

    private fun start(
        capacity: Int = BackgroundTelemetry.HANDOFF_CAPACITY,
        openSender: (EventStore) -> EventSender = { EventSender.None },
    ): BackgroundTelemetry =
        BackgroundTelemetry.start(
            openStore = {
                thread.set(Thread.currentThread())
                store.opened.countDown()
                store
            },
            onFailure = { failures += it },
            capacity = capacity,
            openSender = openSender,
            gate = gate,
        )

    @Test
    fun `an admitted event is captured on the caller's thread with its rate and reaches the store with its class`() {
        gate.config = TelemetryConfig(samplingRate = 0.8)
        val telemetry = start()
        val rates = CopyOnWriteArrayList<SamplingRate>()
        val threads = CopyOnWriteArrayList<Thread>()

        telemetry.emit("load", EventClass.DIAGNOSTIC) { rate ->
            rates += rate
            threads += Thread.currentThread()
            event("load")
        }
        telemetry.emit("failed", EventClass.ERROR) { rate ->
            rates += rate
            threads += Thread.currentThread()
            errorEvent("failed")
        }

        assertEquals(listOf(0.8, 1.0), rates.map { it.fraction })
        assertEquals(listOf(Thread.currentThread(), Thread.currentThread()), threads)
        awaitUntil { store.payloads.size == 2 }
        assertEquals(listOf("load", "failed"), store.payloads)
        assertEquals(listOf(EventClass.DIAGNOSTIC, EventClass.ERROR), store.classes)
    }

    @Test
    fun `capture is never called for an event the gate refuses`() {
        val telemetry = start()
        val captures = AtomicInteger()
        val capture = { _: SamplingRate ->
            captures.incrementAndGet()
            event("refused")
        }

        gate.config = TelemetryConfig(samplingRate = 0.5)
        telemetry.emit("sampled out", EventClass.DIAGNOSTIC, capture)
        gate.config = TelemetryConfig(allowedEvents = setOf("other"))
        telemetry.emit("not allowed", EventClass.DIAGNOSTIC, capture)
        telemetry.emit("not allowed", EventClass.ERROR, capture)
        gate.config = TelemetryConfig(enabled = false)
        telemetry.emit("switched off", EventClass.DIAGNOSTIC, capture)
        telemetry.emit("switched off", EventClass.ERROR, capture)

        gate.config = TelemetryConfig.DEFAULT
        telemetry.emit("marker", EventClass.DIAGNOSTIC) { event("marker") }
        awaitUntil { store.payloads.isNotEmpty() }
        assertEquals(0, captures.get())
        assertEquals(listOf("marker"), store.payloads)
    }

    @Test
    fun `disabled telemetry never calls capture`() {
        val captures = AtomicInteger()

        Telemetry.Disabled.emit("load", EventClass.DIAGNOSTIC) {
            captures.incrementAndGet()
            event("load")
        }
        Telemetry.Disabled.emit("failed", EventClass.ERROR) {
            captures.incrementAndGet()
            errorEvent("failed")
        }

        assertEquals(0, captures.get())
    }

    @Test
    fun `events over the rate limit are not captured and reach the store as rate limited after a sync`() {
        val telemetry = start()
        val captures = AtomicInteger()

        repeat(25) {
            telemetry.emit("load", EventClass.DIAGNOSTIC) {
                captures.incrementAndGet()
                event("load")
            }
        }
        telemetry.sync()

        awaitUntil { store.syncs.get() >= 1 && store.payloads.size == 20 && store.dropped[DropReason.RATE_LIMITED] != null }
        assertEquals(20, captures.get())
        assertEquals(mapOf(DropReason.RATE_LIMITED to 5L), store.dropped)
    }

    @Test
    fun `a capture that throws does not escape emit, stores nothing and leaves telemetry on`() {
        val telemetry = start()

        telemetry.emit("load", EventClass.DIAGNOSTIC) { throw IllegalStateException("capture broke") }
        telemetry.emit("failed", EventClass.ERROR) { throw IllegalStateException("capture broke") }
        telemetry.emit("render", EventClass.DIAGNOSTIC) { event("after") }
        telemetry.sync()

        awaitUntil { store.syncs.get() == 1 && store.payloads.isNotEmpty() }
        assertEquals(listOf("after"), store.payloads)
        assertTrue(store.dropped.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an event admitted and then lost to overflow has used its rate-limit place, and is counted only as overflow`() {
        store.blockAppends(release)
        val telemetry = start(capacity = 1)
        telemetry.emit("load", EventClass.DIAGNOSTIC) { event("first") }
        store.awaitAppendStarted()

        // The thread is held inside the store: the second fills the handoff, the last three overflow.
        (2..5).forEach { telemetry.emit("load", EventClass.DIAGNOSTIC) { _ -> event("e$it") } }
        release.countDown()
        telemetry.sync()

        awaitUntil { store.syncs.get() >= 1 && store.payloads.size == 2 && store.dropped[DropReason.OVERFLOW] == 3L }
        assertEquals(listOf("first", "e2"), store.payloads)
        assertEquals(mapOf(DropReason.OVERFLOW to 3L), store.dropped)
        repeat(15) { assertEquals("place ${it + 1}", SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC)) }
        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
    }

    @Test
    fun `switched off from the start, the sender is never run and the thread sleeps until switched on`() {
        gate.config = TelemetryConfig(enabled = false)
        val sender = ControlledSender(store, due = true)
        val telemetry = start(openSender = { sender })
        assertTrue(store.opened.await(5, TimeUnit.SECONDS))
        // A sync the thread has processed proves its loop is running, not still starting.
        telemetry.sync()
        awaitUntil { store.syncs.get() == 1 }
        awaitSenderStill(sender)

        val calls = sender.calls()
        assertThreadIdle()
        assertEquals(0, sender.runs.get())
        assertEquals("sender calls while off", calls, sender.calls())

        telemetry.configure(TelemetryConfig.DEFAULT)
        awaitUntil { sender.runs.get() > 0 }
    }

    @Test
    fun `switched off, the sender stops and the thread sleeps, and switched back on it runs again unprompted`() {
        val sender = ControlledSender(store, due = true)
        val telemetry = start(openSender = { sender })
        awaitUntil { sender.runs.get() > 0 }

        telemetry.configure(TelemetryConfig(enabled = false))
        awaitSenderStill(sender)
        val runs = sender.runs.get()
        val calls = sender.calls()
        assertThreadIdle()

        assertEquals(runs, sender.runs.get())
        assertEquals("sender calls while off", calls, sender.calls())

        // No emit and no sync: switching back on alone resumes sending.
        telemetry.configure(TelemetryConfig.DEFAULT)
        awaitUntil { sender.runs.get() > runs }
    }

    @Test
    fun `switched off, a sync still reaches the disk, and the sender is still not run`() {
        val sender = ControlledSender(store, due = true)
        val telemetry = start(openSender = { sender })
        awaitUntil { sender.runs.get() > 0 }
        telemetry.configure(TelemetryConfig(enabled = false))
        awaitSenderStill(sender)
        val runs = sender.runs.get()

        telemetry.sync()

        awaitUntil { store.syncs.get() == 1 }
        awaitSenderStill(sender)
        assertEquals(runs, sender.runs.get())
    }

    @Test
    fun `switching off neither sends nor removes what is stored`() {
        val sender = ControlledSender(store, due = false)
        val telemetry = start(openSender = { sender })
        telemetry.emit("load", EventClass.DIAGNOSTIC) { event("load") }
        telemetry.emit("failed", EventClass.ERROR) { errorEvent("failed") }
        telemetry.sync()
        awaitUntil { store.syncs.get() == 1 && store.payloads.size == 2 }
        awaitSenderStill(sender)
        val runs = sender.runs.get()

        telemetry.configure(TelemetryConfig(enabled = false))
        // Were it run now, this sender would take everything out of the store. The sync wakes the thread after it
        // has seen the switch, so it has every chance to send.
        sender.due = true
        telemetry.sync()
        awaitUntil { store.syncs.get() == 2 }
        awaitSenderStill(sender)
        assertThreadIdle()

        assertEquals(runs, sender.runs.get())
        assertEquals(0, store.removes.get())
        assertEquals(listOf("load", "failed"), store.payloads)
        assertEquals(2, store.size)
        assertTrue(store.dropped.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a capture interrupted by an InterruptedException leaves the caller interrupted and stores nothing`() {
        val telemetry = start()

        telemetry.emit("load", EventClass.DIAGNOSTIC) { throw InterruptedException("capture interrupted") }
        // Also clears the flag, so the waits below are not cut short by it.
        val interrupted = Thread.interrupted()

        telemetry.emit("render", EventClass.DIAGNOSTIC) { event("after") }
        telemetry.sync()
        awaitUntil { store.syncs.get() == 1 && store.payloads.isNotEmpty() }
        assertTrue("the caller's interrupt flag was not restored", interrupted)
        assertEquals(listOf("after"), store.payloads)
        assertTrue(store.dropped.isEmpty())
        assertTrue(failures.isEmpty())
    }

    private fun event(text: String) = TelemetryEvent { text.encodeToByteArray() }

    private fun errorEvent(text: String) = object : TelemetryEvent {
        override fun encode() = text.encodeToByteArray()
        override val eventClass = EventClass.ERROR
    }

    /** Waits for [condition]; a failure on the telemetry thread ends the wait at once and is reported as it is. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            failures.firstOrNull()?.let { throw AssertionError("telemetry failed", it) }
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(5)
        }
    }

    /**
     * Waits until the sender has not been called for 20 ms, so a call already under way when telemetry was switched off
     * has ended.
     */
    private fun awaitSenderStill(sender: ControlledSender) {
        var before = sender.calls()
        awaitUntil {
            Thread.sleep(20)
            val now = sender.calls()
            (now == before).also { before = now }
        }
    }

    /**
     * The telemetry thread waits with no timeout: sampled 300 times 1 ms apart it is [Thread.State.WAITING] every time.
     * This catches a spin whatever the thread is spinning on, and also a thread that polls with a timeout, which shows
     * as [Thread.State.TIMED_WAITING].
     */
    private fun assertThreadIdle() {
        val telemetryThread = thread.get()
        val states = (1..300).map {
            telemetryThread.state.also { Thread.sleep(1) }
        }
        val counts = states.groupingBy { it }.eachCount()
        assertTrue("states over 300 samples: $counts", states.all { it == Thread.State.WAITING })
    }

    /**
     * Runs whenever the thread asks while [due] is set, and then takes everything out of the store, as a send that
     * the server accepted would.
     */
    private class ControlledSender(private val store: EventStore, @Volatile var due: Boolean) : EventSender {
        val runs = AtomicInteger()
        val dueAsks = AtomicInteger()

        fun calls() = runs.get() + dueAsks.get()

        override fun afterStart() = Unit
        override fun afterAppend() = Unit
        override fun afterSync() = Unit

        override fun runIfDue() {
            runs.incrementAndGet()
            if (due) store.remove(store.peek(Int.MAX_VALUE, Int.MAX_VALUE))
        }

        override fun millisUntilDue(): Long? {
            dueAsks.incrementAndGet()
            return if (due) 0 else null
        }
    }

    /** Keeps what reaches it in memory, records every call, and can hold the thread inside [append]. */
    private class RecordingStore : EventStore {
        val payloads: MutableList<String> = CopyOnWriteArrayList()
        val classes: MutableList<EventClass> = CopyOnWriteArrayList()
        val syncs = AtomicInteger()
        val removes = AtomicInteger()
        val dropped: MutableMap<DropReason, Long> = ConcurrentHashMap()
        val opened = CountDownLatch(1)

        private val stored: MutableList<StoredEvent> = CopyOnWriteArrayList()
        private val sequence = AtomicLong()
        private val appendStarted = CountDownLatch(1)

        @Volatile
        private var gate: CountDownLatch? = null

        fun blockAppends(until: CountDownLatch) {
            gate = until
        }

        fun awaitAppendStarted() = check(appendStarted.await(5, TimeUnit.SECONDS)) { "no append started" }

        override fun append(payload: ByteArray, eventClass: EventClass) {
            appendStarted.countDown()
            gate?.await()
            payloads += payload.decodeToString()
            classes += eventClass
            stored += StoredEvent(eventClass, EventQueue.QueuedEvent(sequence.incrementAndGet(), payload))
        }

        override fun sync() {
            syncs.incrementAndGet()
        }

        override fun countDropped(reason: DropReason, count: Long) {
            dropped.merge(reason, count, Long::plus)
        }

        override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> = stored.take(maxEvents)

        override fun remove(events: List<StoredEvent>) {
            removes.incrementAndGet()
            stored.removeAll(events.toSet())
            payloads.removeAll(events.map { it.payload.decodeToString() }.toSet())
        }

        override val size: Int get() = stored.size
    }
}
