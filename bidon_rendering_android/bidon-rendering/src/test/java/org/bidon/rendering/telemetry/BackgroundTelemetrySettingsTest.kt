package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [BackgroundTelemetry.applySettings] and the stored-settings load, against the acceptance criteria 4
 * (`malformedBlockKeepsStoredOff`), 10 (the switches take effect through the entry point), 11 (errors ignore server
 * rates), 19 (fresh values beat a late store load) and 20 (a stored "off" applies to the first events).
 *
 * The gate is made unsettled, as in production with a [SettingsTarget]. The session is `abc`, whose position is about
 * 0.728: kept at 0.8, dropped at 0.5.
 */
class BackgroundTelemetrySettingsTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(15)

    @get:Rule
    val temporary = TemporaryFolder()

    private val store = RecordingStore()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val release = CountDownLatch(1)

    @After
    fun releaseEverything() = release.countDown()

    private fun unsettledGate() = TelemetryGate(SessionId.of("abc")!!, RateLimiter(MonotonicClock { 0L }), initial = null)

    private fun settledGate() = TelemetryGate(SessionId.of("abc")!!, RateLimiter(MonotonicClock { 0L }))

    private fun start(
        gate: TelemetryGate,
        settings: SettingsTarget? = null,
        capacity: Int = BackgroundTelemetry.HANDOFF_CAPACITY,
        openSender: (EventStore) -> EventSender = { EventSender.None },
    ): BackgroundTelemetry =
        BackgroundTelemetry.start(
            openStore = { store.also { it.opened.countDown() } },
            onFailure = { failures += it },
            capacity = capacity,
            openSender = openSender,
            gate = gate,
            settings = settings,
        )

    /** A target whose [load] waits for [loadMayFinish] when one is given. */
    private class FakeTarget(private val stored: TelemetryConfig?, private val loadMayFinish: CountDownLatch? = null) :
        SettingsTarget {
        val applied = CopyOnWriteArrayList<TelemetrySettings>()
        val applyThreads = CopyOnWriteArrayList<String>()

        override fun load(): TelemetryConfig? {
            loadMayFinish?.await()
            return stored
        }

        override fun apply(settings: TelemetrySettings) {
            applied += settings
            applyThreads += Thread.currentThread().name
        }
    }

    private fun event(text: String) = TelemetryEvent { text.encodeToByteArray() }

    private fun errorEvent(text: String) = object : TelemetryEvent {
        override fun encode() = text.encodeToByteArray()
        override val eventClass = EventClass.ERROR
    }

    private fun BackgroundTelemetry.emitDiagnostic(name: String, rates: MutableList<Double> = CopyOnWriteArrayList()) =
        emit(name, EventClass.DIAGNOSTIC) { rate ->
            rates += rate.fraction
            event(name)
        }

    private fun settleFor(millis: Long = 150) = Thread.sleep(millis)

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            failures.firstOrNull()?.let { throw AssertionError("telemetry failed", it) }
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(5)
        }
    }

    // Stored load

    @Test
    fun `the stored switches are loaded before the store is opened and settle the gate`() {
        val loadMayFinish = CountDownLatch(1)
        val gate = unsettledGate()
        val target = FakeTarget(TelemetryConfig(samplingRate = 0.3), loadMayFinish)
        start(gate, target)

        settleFor()
        assertFalse(gate.isSettled)
        assertEquals("the store opens after the load", 1L, store.opened.count)

        loadMayFinish.countDown()
        awaitUntil { gate.isSettled && store.opened.count == 0L }
        assertEquals(TelemetryConfig(samplingRate = 0.3), gate.config)
    }

    @Test
    fun `nothing stored settles the gate on the defaults`() {
        val gate = unsettledGate()
        start(gate, FakeTarget(stored = null))

        awaitUntil { gate.isSettled }

        assertEquals(TelemetryConfig.DEFAULT, gate.config)
    }

    @Test
    fun `events emitted before the load returns are held, then captured in order with the stored switches`() {
        val loadMayFinish = CountDownLatch(1)
        val telemetry = start(unsettledGate(), FakeTarget(TelemetryConfig(samplingRate = 0.8), loadMayFinish))
        val rates = CopyOnWriteArrayList<Double>()

        telemetry.emitDiagnostic("one", rates)
        telemetry.emitDiagnostic("two", rates)
        telemetry.emit("three", EventClass.ERROR) { rate ->
            rates += rate.fraction
            errorEvent("three")
        }
        settleFor()
        assertTrue("held, not admitted under the defaults", rates.isEmpty() && store.payloads.isEmpty())

        loadMayFinish.countDown()

        awaitUntil { store.payloads.size == 3 }
        assertEquals(listOf("one", "two", "three"), store.payloads)
        assertEquals(listOf(0.8, 0.8, 1.0), rates)
    }

    @Test
    fun storedOffAppliesToFirstEvents() {
        val loadMayFinish = CountDownLatch(1)
        val gate = unsettledGate()
        val telemetry = start(gate, FakeTarget(TelemetryConfig(enabled = false), loadMayFinish))
        val captures = AtomicInteger()

        telemetry.emit("load", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("load") }
        telemetry.emit("failed", EventClass.ERROR) { captures.incrementAndGet(); errorEvent("failed") }
        loadMayFinish.countDown()
        awaitUntil { gate.isSettled }
        settleFor()
        telemetry.emit("later", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("later") }
        settleFor()

        assertEquals(0, captures.get())
        assertTrue(store.payloads.isEmpty())
        assertTrue(failures.isEmpty())
    }

    // Fresh beats late load

    @Test
    fun freshValuesBeatLateStoreLoad() {
        val loadMayFinish = CountDownLatch(1)
        val gate = unsettledGate()
        val target = FakeTarget(TelemetryConfig(enabled = false), loadMayFinish)
        val telemetry = start(gate, target)
        val fresh = TelemetrySettings(null, TelemetryConfig(samplingRate = 0.8))

        telemetry.applySettings(fresh)
        loadMayFinish.countDown()
        awaitUntil { target.applied.size == 1 }
        val rates = CopyOnWriteArrayList<Double>()
        telemetry.emitDiagnostic("after", rates)

        awaitUntil { store.payloads.size == 1 }
        assertEquals(TelemetryConfig(samplingRate = 0.8), gate.config)
        assertEquals(listOf(0.8), rates)
        assertEquals(listOf(fresh), target.applied)
    }

    @Test
    fun `fresh settings with only an endpoint leave the stored switches in force`() {
        val loadMayFinish = CountDownLatch(1)
        val gate = unsettledGate()
        val target = FakeTarget(TelemetryConfig(enabled = false), loadMayFinish)
        val telemetry = start(gate, target)

        telemetry.applySettings(TelemetrySettings(testEndpoint(), null))
        loadMayFinish.countDown()
        awaitUntil { gate.isSettled && target.applied.size == 1 }
        val captures = AtomicInteger()
        telemetry.emit("load", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("load") }
        settleFor()

        assertEquals(TelemetryConfig(enabled = false), gate.config)
        assertEquals(0, captures.get())
    }

    // Reaching the target

    @Test
    fun `settings reach the target on the telemetry thread, in order`() {
        val target = FakeTarget(stored = null)
        val telemetry = start(unsettledGate(), target)
        val delivered = listOf(
            TelemetrySettings(testEndpoint(), null),
            TelemetrySettings(null, TelemetryConfig(samplingRate = 0.1)),
            TelemetrySettings.NONE,
            fullSettings(),
        )

        delivered.forEach(telemetry::applySettings)

        awaitUntil { target.applied.size == delivered.size }
        assertEquals(delivered, target.applied)
        assertEquals(List(delivered.size) { "bidon-telemetry" }, target.applyThreads)
    }

    @Test
    fun `settings are not lost when the handoff is full, and still arrive in order`() {
        val target = FakeTarget(stored = null)
        store.blockAppends(release)
        val telemetry = start(unsettledGate(), target, capacity = 1)
        telemetry.emit("load", EventClass.DIAGNOSTIC) { event("first") }
        store.awaitAppendStarted()
        telemetry.emit("load", EventClass.DIAGNOSTIC) { event("fills the handoff") }
        val delivered = (1..5).map { TelemetrySettings(null, TelemetryConfig(samplingRate = it / 10.0)) }

        delivered.forEach(telemetry::applySettings)
        release.countDown()

        awaitUntil { target.applied.size == delivered.size }
        assertEquals(delivered, target.applied)
        assertEquals(TelemetryConfig(samplingRate = 0.5), delivered.last().config)
    }

    @Test
    fun `settings with switches replace the gate's at once, on the caller's thread`() {
        val gate = unsettledGate()
        val telemetry = start(gate, FakeTarget(stored = null))
        val config = TelemetryConfig(samplingRate = 0.4)

        telemetry.applySettings(TelemetrySettings(null, config))

        assertEquals(config, gate.config)
    }

    @Test
    fun `settings with no switches leave the gate's alone`() {
        val gate = settledGate()
        val telemetry = start(gate)
        val config = TelemetryConfig(samplingRate = 0.4)
        gate.config = config

        telemetry.applySettings(TelemetrySettings.NONE)
        telemetry.applySettings(TelemetrySettings(testEndpoint(), null))

        assertEquals(config, gate.config)
    }

    @Test
    fun `without a settings target applySettings still drives the gate`() {
        val gate = settledGate()
        val telemetry = start(gate)

        telemetry.applySettings(TelemetrySettings(null, TelemetryConfig(enabled = false)))

        assertFalse(gate.config.enabled)
        assertTrue(failures.isEmpty())
    }

    // AC 4

    @Test
    fun malformedBlockKeepsStoredOff() {
        val folder = temporary.newFolder()
        val file = SettingsFile { File(folder, SettingsFile.FILE_NAME) }
        file.write(TelemetrySettings(null, TelemetryConfig(enabled = false)))
        val configured = ConfiguredSender(file) { _, _ -> EventSender.None }
        val gate = unsettledGate()
        val telemetry = start(gate, configured, openSender = { store ->
            configured.attach(store)
            configured
        })
        val malformed = JSONObject("""{"url": "$TEST_URL", "app_key": "k", "enabled": "yes", "sample_rate": 0.5}""")

        telemetry.applySettings(TelemetryBlock.parse(malformed, TEST_BUNDLE, TEST_SDK_VERSION, null))
        awaitUntil { gate.isSettled && file.read().endpoint != null }
        val captures = AtomicInteger()
        telemetry.emit("load", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("load") }
        settleFor()

        assertFalse(gate.config.enabled)
        assertEquals(0, captures.get())
        assertEquals("the good endpoint is kept", TEST_URL, file.read().endpoint?.url?.toString())
        assertEquals("the stored switches are untouched", TelemetryConfig(enabled = false), file.read().config)
    }

    // AC 10

    @Test
    fun switchOffKeepsStored() {
        val sender = ControlledSender(store)
        val telemetry = start(settledGate(), openSender = { sender })
        telemetry.emit("load", EventClass.DIAGNOSTIC) { event("load") }
        telemetry.emit("failed", EventClass.ERROR) { errorEvent("failed") }
        telemetry.sync()
        awaitUntil { store.syncs.get() == 1 && store.payloads.size == 2 }
        awaitStill(sender)

        telemetry.applySettings(TelemetrySettings(null, TelemetryConfig(enabled = false)))
        // Were it run now, this sender would take everything out of the store.
        sender.due = true
        telemetry.sync()
        awaitUntil { store.syncs.get() == 2 }
        awaitStill(sender)
        val runs = sender.runs.get()
        val captures = AtomicInteger()
        telemetry.emit("load", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("more") }
        telemetry.emit("failed", EventClass.ERROR) { captures.incrementAndGet(); errorEvent("more") }
        settleFor()

        assertEquals("no capture while off", 0, captures.get())
        assertEquals("no send while off", runs, sender.runs.get())
        assertEquals(0, store.removes.get())
        assertEquals(listOf("load", "failed"), store.payloads)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun zeroRateDropsDiagnostic() {
        val telemetry = start(settledGate())
        val captures = AtomicInteger()

        telemetry.applySettings(TelemetrySettings(null, TelemetryConfig(samplingRate = 0.0)))
        telemetry.emit("load", EventClass.DIAGNOSTIC) { captures.incrementAndGet(); event("load") }
        telemetry.emit("failed", EventClass.ERROR) { captures.incrementAndGet(); errorEvent("failed") }

        awaitUntil { store.payloads.size == 1 }
        assertEquals(1, captures.get())
        assertEquals(listOf("failed"), store.payloads)
    }

    @Test
    fun perEventRateOverridesGlobal() {
        val telemetry = start(settledGate())
        val rates = CopyOnWriteArrayList<Double>()

        telemetry.applySettings(
            TelemetrySettings(null, TelemetryConfig(samplingRate = 0.5, eventRates = mapOf("load" to 0.8))),
        )
        telemetry.emitDiagnostic("load", rates)
        telemetry.emitDiagnostic("render", rates)

        awaitUntil { store.payloads.isNotEmpty() }
        settleFor()
        assertEquals(listOf("load"), store.payloads)
        assertEquals(listOf(0.8), rates)
    }

    // AC 11

    @Test
    fun errorsIgnoreServerRates() {
        val telemetry = start(settledGate())
        val rates = CopyOnWriteArrayList<Double>()

        telemetry.applySettings(
            TelemetrySettings(null, TelemetryConfig(samplingRate = 0.0, eventRates = mapOf("failed" to 0.0))),
        )
        telemetry.emit("failed", EventClass.ERROR) { rate ->
            rates += rate.fraction
            errorEvent("failed")
        }

        awaitUntil { store.payloads.size == 1 }
        assertEquals(listOf(1.0), rates)
        assertEquals(listOf(EventClass.ERROR), store.classes)
    }

    // Helpers

    /** Waits until the sender has not been called for 20 ms. */
    private fun awaitStill(sender: ControlledSender) {
        var before = sender.calls()
        awaitUntil {
            Thread.sleep(20)
            val now = sender.calls()
            (now == before).also { before = now }
        }
    }

    /** Takes everything out of the store when it runs while [due] is set, as an accepted send would. */
    private class ControlledSender(private val store: EventStore) : EventSender {
        @Volatile
        var due = false
        val runs = AtomicInteger()
        private val dueAsks = AtomicInteger()

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

    /** Keeps what reaches it in memory, and can hold the thread inside [append]. */
    private class RecordingStore : EventStore {
        val payloads: MutableList<String> = CopyOnWriteArrayList()
        val classes: MutableList<EventClass> = CopyOnWriteArrayList()
        val syncs = AtomicInteger()
        val removes = AtomicInteger()
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

        override fun countDropped(reason: DropReason, count: Long) = Unit

        override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> = stored.take(maxEvents)

        override fun remove(events: List<StoredEvent>) {
            removes.incrementAndGet()
            stored.removeAll(events.toSet())
            payloads.removeAll(events.map { it.payload.decodeToString() }.toSet())
        }

        override val size: Int get() = stored.size
    }

    @Test
    fun `a gate that is settled already cannot be paired with stored settings`() {
        val settled = TelemetryGate(SessionId.of("abc")!!, RateLimiter(MonotonicClock { 0L }))
        val target = object : SettingsTarget {
            override fun load(): TelemetryConfig? = null
            override fun apply(settings: TelemetrySettings) = Unit
        }

        val refused = runCatching {
            BackgroundTelemetry.start(openStore = { error("never opened") }, onFailure = {}, gate = settled, settings = target)
        }

        assertTrue("started with a settled gate", refused.exceptionOrNull() is IllegalArgumentException)
    }
}
