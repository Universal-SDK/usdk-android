package org.bidon.rendering.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The ad path calls [Telemetry.emit] and [Telemetry.sync]. Whatever the store does, slow, full or failing, those calls
 * must return at once and never throw.
 */
class BackgroundTelemetryTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** A call that blocks when it must not fails its test instead of hanging the run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val store = RecordingStore()
    private val failures = CopyOnWriteArrayList<Throwable>()

    /** Set by the tests that make telemetry fail on purpose. */
    private var failureExpected = false

    /** Released at the end of every test, so no telemetry thread stays blocked inside a store after its test. */
    private val release = CountDownLatch(1)

    @After
    fun releaseStore() = release.countDown()

    /**
     * The thread the last [start] created, captured from inside the store's opening, which runs on it. Threads from
     * earlier tests may still be alive, waiting for events, so it cannot be found by name.
     */
    private val thread = AtomicReference<Thread>()

    /** Lets every event through: the default config, and a rate limit no test reaches. */
    private val openGate = TelemetryGate(SessionId.of("test")!!, RateLimiter(MonotonicClock { 0L }, Int.MAX_VALUE))

    private fun start(
        capacity: Int = BackgroundTelemetry.HANDOFF_CAPACITY,
        onFailure: (Throwable) -> Unit = { failures += it },
        openSender: (EventStore) -> EventSender = { EventSender.None },
        openStore: () -> EventStore = { store },
    ): Telemetry {
        val capturingOpen = {
            thread.set(Thread.currentThread())
            openStore()
        }
        return BackgroundTelemetry.start(capturingOpen, onFailure, capacity, openSender, openGate)
    }

    /** Hands [event] over through the gate, which [openGate] never refuses, so these tests see only the thread. */
    private fun Telemetry.emit(event: TelemetryEvent) = emit("event", event.eventClass) { event }

    @Test
    fun `events reach the store in the order they were emitted, as diagnostics unless they say otherwise`() {
        val telemetry = start()

        (1..50).forEach { telemetry.emit(event("e$it")) }

        awaitUntil { store.payloads.size == 50 }
        assertEquals((1..50).map { "e$it" }, store.payloads)
        assertEquals(setOf(EventClass.DIAGNOSTIC), store.classes.toSet())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an event reaches the store with the class it declares`() {
        val telemetry = start()

        telemetry.emit(event("diagnostic"))
        telemetry.emit(errorEvent("error"))

        awaitUntil { store.payloads.size == 2 }
        assertEquals(listOf(EventClass.DIAGNOSTIC, EventClass.ERROR), store.classes)
    }

    @Test
    fun `emit returns at once while the store is stuck`() {
        store.blockAppends(release)
        val telemetry = start()
        telemetry.emit(event("first"))
        store.awaitAppendStarted()

        // The thread is stuck inside the store. Every one of these would hang the test if emit waited for it.
        repeat(10_000) { telemetry.emit(event("more")) }
        telemetry.sync()
    }

    @Test
    fun `events that do not fit the handoff are counted as overflow and the rest are kept`() {
        store.blockAppends(release)
        val telemetry = start(capacity = 4)
        telemetry.emit(event("first"))
        store.awaitAppendStarted()

        (1..4).forEach { telemetry.emit(event("kept$it")) }
        repeat(3) { telemetry.emit(event("lost")) }
        release.countDown()

        awaitUntil { store.payloads.size == 5 && store.dropped[DropReason.OVERFLOW] == 3L }
        assertEquals(listOf("first", "kept1", "kept2", "kept3", "kept4"), store.payloads)
        assertEquals(mapOf(DropReason.OVERFLOW to 3L), store.dropped)
    }

    @Test
    fun `a backlog records its overflow in one write, once the thread has caught up`() {
        val telemetry = start(capacity = 2)
        var refills = 5
        // Each of the first appends hands over more than the handoff can take, so events keep overflowing while the
        // thread works through its backlog.
        store.onAppend = {
            if (refills-- > 0) repeat(3) { telemetry.emit(event("more")) }
        }

        telemetry.emit(event("first"))

        awaitUntil { store.payloads.size + (store.dropped[DropReason.OVERFLOW] ?: 0L) == 16L }
        assertTrue(store.dropped.getValue(DropReason.OVERFLOW) > 0)
        assertEquals(1, store.countDroppedCalls.get())
    }

    @Test
    fun `a sync requested while the handoff is full still reaches the store`() {
        store.blockAppends(release)
        val telemetry = start(capacity = 4)
        telemetry.emit(event("first"))
        store.awaitAppendStarted()
        repeat(4) { telemetry.emit(event("filler")) }

        telemetry.sync()
        release.countDown()

        awaitUntil { store.syncs.get() == 1 }
        awaitUntil { store.payloads.size == 5 }
        assertEquals(1, store.syncs.get())
    }

    @Test
    fun `a sync on an idle thread reaches the store and stores nothing`() {
        val telemetry = start()
        telemetry.emit(event("only"))
        awaitUntil { store.payloads.size == 1 }

        telemetry.sync()

        awaitUntil { store.syncs.get() == 1 }
        assertEquals(listOf("only"), store.payloads)
    }

    @Test
    fun `an event that fails to encode switches telemetry off and is reported once`() {
        failureExpected = true
        val broken = IllegalStateException("broken event")
        val telemetry = start()

        telemetry.emit(event("before"))
        telemetry.emit { throw broken }
        awaitUntil { failures.isNotEmpty() }
        telemetry.emit(event("after"))
        telemetry.sync()

        assertStaysOff(telemetry)
        assertEquals(listOf("before"), store.payloads)
        assertEquals(0, store.syncs.get())
        assertEquals(1, failures.size)
        assertSame(broken, failures.single())
    }

    @Test
    fun `a store that throws switches telemetry off and is reported once`() {
        failureExpected = true
        store.failAppends(OutOfMemoryError("store"))
        val telemetry = start()

        telemetry.emit(event("a"))
        awaitUntil { failures.isNotEmpty() }
        repeat(10) { telemetry.emit(event("b")) }
        telemetry.sync()

        assertStaysOff(telemetry)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is OutOfMemoryError)
    }

    @Test
    fun `a store that cannot be opened switches telemetry off and is reported once`() {
        failureExpected = true
        val telemetry = start { throw IOException("no disk") }

        awaitUntil { failures.isNotEmpty() }
        telemetry.emit(event("a"))
        telemetry.sync()

        assertStaysOff(telemetry)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
    }

    @Test
    fun `once telemetry is off it holds on to no event, waiting or new`() {
        store.blockAppends(release)
        store.failAppends(IllegalStateException("store"))
        val telemetry = start()
        telemetry.emit(event("first"))
        store.awaitAppendStarted()

        val waiting = (1..10).map { WeakReference(event("waiting")).also { telemetry.emit(it.get()!!) } }
        release.countDown()
        awaitThreadGone()
        val late = (1..10).map { WeakReference(event("late")).also { telemetry.emit(it.get()!!) } }

        awaitUntil {
            System.gc()
            (waiting + late).all { it.get() == null }
        }
    }

    @Test
    fun `a failure while reporting a failure does not escape the telemetry thread`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val reported = CountDownLatch(1)
            val telemetry = start(
                onFailure = {
                    reported.countDown()
                    throw IllegalStateException("logger broke")
                },
                openStore = { throw IOException("no disk") },
            )
            assertTrue(reported.await(5, TimeUnit.SECONDS))

            telemetry.emit(event("a"))
            awaitThreadGone()
            assertTrue(uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the disk store keeps what was emitted, and a later process restores it`() {
        val directory = temporary.newFolder()
        val telemetry = start { DiskEventStore.open(directory) }
        val written = CountDownLatch(1)

        (1..3).forEach { telemetry.emit(event("e$it")) }
        // Encoded only after the three before it are on disk. It then holds the thread, so the files stay still.
        telemetry.emit {
            written.countDown()
            release.await()
            ByteArray(0)
        }
        assertTrue(written.await(5, TimeUnit.SECONDS))

        val copy = temporary.newFolder()
        File(directory, "queue").copyTo(File(copy, "queue"))
        val restored = EventQueue.open(File(copy, "queue"), DroppedEvents.open(File(copy, "dropped")))
        assertEquals(
            listOf("e1", "e2", "e3"),
            restored.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload.decodeToString() },
        )
        assertTrue(failures.isEmpty())
    }

    /** Telemetry has switched off: its thread is gone, and nothing more reaches the store. */
    private fun assertStaysOff(telemetry: Telemetry) {
        awaitThreadGone()
        val stored = store.payloads.toList()
        telemetry.emit(event("late"))
        telemetry.sync()
        Thread.sleep(50)
        assertEquals(stored, store.payloads)
    }

    private fun awaitThreadGone() = awaitUntil { thread.get()?.isAlive == false }

    @Test
    fun `the sender is given the store and hears about the start, each stored event and each sync, in order`() {
        val sender = RecordingSender(store)
        val given = AtomicReference<EventStore>()
        val telemetry = start(
            openSender = { opened ->
                given.set(opened)
                sender
            },
        )

        telemetry.emit(event("a"))
        telemetry.emit(event("b"))
        awaitUntil { store.payloads.size == 2 }
        telemetry.sync()
        awaitUntil { sender.calls.any { it.startsWith("sync") } }

        assertSame(store, given.get())
        // Each count is what the store held when the sender heard: after the append, after the sync.
        assertEquals(listOf("start", "append 1", "append 2", "sync 1"), sender.calls)
    }

    @Test
    fun `the sender runs on the telemetry thread when it is due, with no event to wake the thread`() {
        val sender = RecordingSender(store).apply { dueIn = 10 }

        start(openSender = { sender })

        awaitUntil { sender.calls.count { it == "run" } >= 5 }
        assertEquals(setOf(thread.get()), sender.threads.toSet())
    }

    @Test
    fun `events are stored at once while the sender waits for its next run`() {
        val sender = RecordingSender(store).apply { dueIn = 60_000 }
        val telemetry = start(openSender = { sender })

        telemetry.emit(event("a"))

        awaitUntil { store.payloads == listOf("a") }
    }

    @Test
    fun `a sender that throws switches telemetry off and is reported once`() {
        failureExpected = true
        val sender = RecordingSender(store).apply { failure = IllegalStateException("sender") }

        val telemetry = start(openSender = { sender })
        awaitUntil { failures.isNotEmpty() }
        telemetry.emit(event("a"))

        assertStaysOff(telemetry)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
    }

    private fun event(text: String) = TelemetryEvent { text.encodeToByteArray() }

    private fun errorEvent(text: String) = object : TelemetryEvent {
        override fun encode() = text.encodeToByteArray()
        override val eventClass = EventClass.ERROR
    }

    /**
     * Waits for [condition]. Unless the test expects telemetry to fail, a failure on the telemetry thread ends the
     * wait at once and is reported as it is, instead of as a condition that was never met.
     */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (!failureExpected) failures.firstOrNull()?.let { throw AssertionError("telemetry failed", it) }
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(5)
        }
    }

    /** Records what reaches it. Can be made to hold the thread inside [append], or to throw from it. */
    /** Records what the telemetry thread tells it, and runs as often as [dueIn] asks. */
    private class RecordingSender(private val store: RecordingStore) : EventSender {

        val calls: MutableList<String> = CopyOnWriteArrayList()
        val threads: MutableList<Thread> = CopyOnWriteArrayList()

        @Volatile
        var dueIn: Long? = null

        @Volatile
        var failure: Throwable? = null

        override fun afterStart() {
            calls += "start"
        }

        override fun afterAppend() {
            calls += "append ${store.payloads.size}"
        }

        override fun afterSync() {
            calls += "sync ${store.syncs.get()}"
        }

        override fun runIfDue() {
            threads += Thread.currentThread()
            failure?.let { throw it }
            if (dueIn != null) calls += "run"
        }

        override fun millisUntilDue(): Long? = dueIn
    }

    private class RecordingStore : WriteOnlyStore() {

        val payloads: MutableList<String> = CopyOnWriteArrayList()
        val classes: MutableList<EventClass> = CopyOnWriteArrayList()
        val syncs = AtomicInteger()
        val dropped: MutableMap<DropReason, Long> = ConcurrentHashMap()
        val countDroppedCalls = AtomicInteger()

        /** Runs on the telemetry thread at the start of every append. */
        @Volatile
        var onAppend: () -> Unit = {}

        private val appendStarted = CountDownLatch(1)

        @Volatile
        private var gate: CountDownLatch? = null

        @Volatile
        private var failure: Throwable? = null

        fun blockAppends(until: CountDownLatch) {
            gate = until
        }

        fun failAppends(with: Throwable) {
            failure = with
        }

        fun awaitAppendStarted() = check(appendStarted.await(5, TimeUnit.SECONDS)) { "no append started" }

        override fun append(payload: ByteArray, eventClass: EventClass) {
            appendStarted.countDown()
            onAppend()
            gate?.await()
            failure?.let { throw it }
            payloads += payload.decodeToString()
            classes += eventClass
        }

        override fun sync() {
            syncs.incrementAndGet()
        }

        override fun countDropped(reason: DropReason, count: Long) {
            countDroppedCalls.incrementAndGet()
            dropped.merge(reason, count, Long::plus)
        }
    }
}
