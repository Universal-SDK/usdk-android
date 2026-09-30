package org.bidon.rendering.telemetry

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The sender of D-028 against a real store and a real HTTP server, with time and chance under the test's control.
 * Each stored event is `{"n":<number>}`, so a request can be read back as the numbers it carried.
 */
class BatchSenderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = MockWebServer().apply { start() }
    private val directory by lazy { temporary.newFolder() }
    private val wallClock = MutableClock(now = 1_800_000_000_000L)
    private val store by lazy { DiskEventStore.open(directory, wallClock) }
    private val time = ManualTime()

    private val endpoint by lazy {
        TelemetryEndpoint.of(server.url("/v2/telemetry").toString(), "app_key", "com.example", "0.14.0")!!
    }
    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!

    @After
    fun stopServer() = server.shutdown()

    private fun sender(random: Random = Random(seed = 7)) =
        BatchSender(store, HttpTelemetryClient(endpoint, readTimeoutMillis = 2_000), BatchBody(endpoint, session), time, random)

    private fun storeEvents(numbers: IntRange, eventClass: EventClass = EventClass.DIAGNOSTIC) {
        for (n in numbers) store.append("""{"n":$n}""".encodeToByteArray(), eventClass)
    }

    /** Stores the events one by one, telling [sender] after each, as the telemetry thread does. */
    private fun BatchSender.stored(numbers: IntRange, eventClass: EventClass = EventClass.DIAGNOSTIC) {
        for (n in numbers) {
            storeEvents(n..n, eventClass)
            afterAppend()
        }
    }

    private fun answer(vararg statuses: Int) {
        for (status in statuses) server.enqueue(MockResponse().setResponseCode(status))
    }

    private fun answerRetryAfter(status: Int, seconds: String) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", seconds))
    }

    /** The numbers each request carried, in the order the requests arrived. */
    private fun requests(): List<List<Int>> = List(server.requestCount - taken) {
        taken++
        numbersIn(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
    }

    private var taken = 0

    private fun remaining(): List<Int> =
        store.peek(maxEvents = 10_000, maxPayloadBytes = 1 shl 20).map { JSONObject(it.payload.decodeToString()).getInt("n") }

    private fun dropped(): Map<DropReason, Long> = DroppedEvents.open(File(directory, "dropped")).snapshot()

    /** Moves time to when [sender] is due, and lets it send. */
    private fun BatchSender.runWhenDue(): Long {
        val wait = millisUntilDue()!!
        time.now += wait
        runIfDue()
        return wait
    }

    // Triggers

    @Test
    fun `events an earlier process left are sent as soon as the store opens`() {
        storeEvents(0..2)
        answer(202)
        val sender = sender()

        sender.afterStart()

        assertEquals(0L, sender.millisUntilDue())
        sender.runIfDue()
        assertEquals(listOf(listOf(0, 1, 2)), requests())
        assertEquals(emptyList<Int>(), remaining())
        assertNull(sender.millisUntilDue())
    }

    @Test
    fun `an empty store at start schedules nothing`() {
        val sender = sender()

        sender.afterStart()
        sender.runIfDue()

        assertNull(sender.millisUntilDue())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a stored event waits a minute for others to join its batch`() {
        answer(202)
        val sender = sender().apply { afterStart() }

        sender.stored(0..0)
        assertEquals(60_000L, sender.millisUntilDue())
        time.now += 59_999
        sender.runIfDue()
        assertEquals(0, server.requestCount)

        time.now += 1
        sender.runIfDue()
        assertEquals(listOf(listOf(0)), requests())
    }

    @Test
    fun `later events do not push the timer back`() {
        val sender = sender().apply { afterStart() }

        sender.stored(0..0)
        time.now += 30_000
        sender.stored(1..1)

        assertEquals(30_000L, sender.millisUntilDue())
    }

    @Test
    fun `a full batch is sent at once`() {
        val sender = sender().apply { afterStart() }

        sender.stored(0..98)
        assertEquals(60_000L, sender.millisUntilDue())
        sender.stored(99..99)

        assertEquals(0L, sender.millisUntilDue())
    }

    @Test
    fun `the app going to the background sends at once`() {
        val sender = sender().apply { afterStart() }
        sender.stored(0..0)

        sender.afterSync()

        assertEquals(0L, sender.millisUntilDue())
    }

    // Passes

    @Test
    fun `a pass sends batch after batch until the store is empty`() {
        storeEvents(0..249)
        answer(202, 202, 202)
        val sender = sender().apply { afterStart() }

        sender.runIfDue()

        assertEquals(listOf(100, 100, 50), requests().map { it.size })
        assertEquals(emptyList<Int>(), remaining())
        assertNull(sender.millisUntilDue())
    }

    @Test
    fun `errors are sent before diagnostics`() {
        storeEvents(0..1, EventClass.DIAGNOSTIC)
        storeEvents(2..3, EventClass.ERROR)
        answer(202)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(listOf(2, 3, 0, 1)), requests())
    }

    @Test
    fun `a failed request keeps its batch and ends the pass`() {
        storeEvents(0..149)
        answer(500)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(1, requests().size)
        assertEquals((0..149).toList(), remaining())
    }

    @Test
    fun `a retry sends the bytes that were stored`() {
        // Spacing and key order that decoding and encoding again would not keep.
        val stored = listOf("""{ "z":1, "n":0 }""", """{"n":1,"a":"é"}""")
        stored.forEach { store.append(it.encodeToByteArray(), EventClass.ERROR) }
        answer(503, 202)
        val sender = sender().apply { afterStart() }

        sender.runIfDue()
        sender.runWhenDue()

        val bodies = List(2) { server.takeRequest(1, TimeUnit.SECONDS)!!.body.readByteArray() }
        assertArrayEquals(bodies[0], bodies[1])
        assertArrayEquals(BatchBody(endpoint, session).encode(stored.map { it.encodeToByteArray() }), bodies[1])
        assertEquals(emptyList<Int>(), remaining())
    }

    @Test
    fun `events past their TTL are not sent, and are counted`() {
        storeEvents(0..1, EventClass.DIAGNOSTIC)
        storeEvents(2..2, EventClass.ERROR)
        wallClock.now += TimeUnit.MINUTES.toMillis(16)
        answer(202)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(listOf(2)), requests())
        assertEquals(2L, dropped()[DropReason.EXPIRED])
    }

    // Retries

    @Test
    fun `retries wait 5, 10, 20 and 40 seconds, of which the second half is random`() {
        storeEvents(0..0)
        for ((random, expected) in listOf(
            LowestRandom to listOf(2_500L, 5_000L, 10_000L, 20_000L),
            HighestRandom to listOf(5_000L, 10_000L, 20_000L, 40_000L),
        )) {
            repeat(4) { answer(500) }
            val sender = sender(random).apply { afterStart() }
            sender.runIfDue()

            val waits = mutableListOf(sender.millisUntilDue()!!)
            repeat(3) {
                sender.runWhenDue()
                waits += sender.millisUntilDue()!!
            }

            assertEquals(expected, waits)
            assertEquals(4, requests().size)
        }
    }

    @Test
    fun `after the fifth failed attempt the next pass waits for the timer, and retries start over`() {
        storeEvents(0..0)
        repeat(6) { answer(500) }
        val sender = sender(HighestRandom).apply { afterStart() }

        sender.runIfDue()
        repeat(4) { sender.runWhenDue() }
        assertEquals(5, requests().size)
        assertEquals(60_000L, sender.millisUntilDue())

        sender.runWhenDue()
        assertEquals(5_000L, sender.millisUntilDue())
        assertEquals(listOf(0), remaining())
    }

    @Test
    fun `a request that gets through ends the series of retries`() {
        storeEvents(0..0)
        answer(500, 500, 202)
        val sender = sender(HighestRandom).apply { afterStart() }

        sender.runIfDue()
        sender.runWhenDue()
        sender.runWhenDue()

        assertEquals(emptyList<Int>(), remaining())
        assertNull(sender.millisUntilDue())
        sender.stored(1..1)
        assertEquals(60_000L, sender.millisUntilDue())
    }

    @Test
    fun `new events do not bring a scheduled retry forward`() {
        storeEvents(0..0)
        answer(500)
        val sender = sender(HighestRandom).apply { afterStart() }
        sender.runIfDue()

        sender.stored(1..150)

        assertEquals(5_000L, sender.millisUntilDue())
    }

    @Test
    fun `the app going to the background tries once during a back-off`() {
        storeEvents(0..0)
        answer(500, 202)
        val sender = sender(HighestRandom).apply { afterStart() }
        sender.runIfDue()
        time.now += 1_000

        sender.afterSync()
        sender.runIfDue()

        assertEquals(2, requests().size)
        assertEquals(emptyList<Int>(), remaining())
    }

    @Test
    fun `a Retry-After of up to 30 seconds is waited for exactly`() {
        storeEvents(0..0)
        answerRetryAfter(429, "7")
        answerRetryAfter(503, "30")
        val sender = sender().apply { afterStart() }

        sender.runIfDue()
        assertEquals(7_000L, sender.runWhenDue())
        assertEquals(30_000L, sender.millisUntilDue())
    }

    @Test
    fun `a longer Retry-After ends the pass, and nothing is sent before it has passed`() {
        storeEvents(0..0)
        answerRetryAfter(503, "120")
        answer(202, 202)
        val sender = sender().apply { afterStart() }
        sender.runIfDue()
        assertEquals(120_000L, sender.millisUntilDue())

        time.now += 10_000
        sender.afterSync()
        sender.stored(1..150)
        sender.runIfDue()

        assertEquals(110_000L, sender.millisUntilDue())
        assertEquals(1, requests().size)
        sender.runWhenDue()
        assertEquals(listOf(100, 51), requests().map { it.size })
    }

    @Test
    fun `a Retry-After longer than a day holds sending back for a day`() {
        storeEvents(0..0)
        answerRetryAfter(503, "31536000")
        val sender = sender().apply { afterStart() }

        sender.runIfDue()

        assertEquals(TimeUnit.HOURS.toMillis(24), sender.millisUntilDue())
    }

    // Answers the server gives for the events themselves

    @Test
    fun `a batch over the server's size limit is sent in halves until the parts fit`() {
        server.dispatcher = answering { numbers -> if (numbers.size > 25) 413 else 202 }
        storeEvents(0..99)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(100, 50, 25, 25, 50, 25, 25), requests().map { it.size })
        assertEquals(emptyList<Int>(), remaining())
        assertNull(dropped()[DropReason.REJECTED])
    }

    @Test
    fun `a single event over the size limit is dropped and counted, and the others are delivered`() {
        server.dispatcher = answering { numbers -> if (1 in numbers) 413 else 202 }
        storeEvents(0..2)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(listOf(0, 1, 2), listOf(0), listOf(1, 2), listOf(1), listOf(2)), requests())
        assertEquals(emptyList<Int>(), remaining())
        assertEquals(1L, dropped()[DropReason.REJECTED])
    }

    @Test
    fun `a refused batch is dropped and counted, and the pass goes on`() {
        storeEvents(0..149)
        answer(400, 202)
        val sender = sender().apply { afterStart() }

        sender.runIfDue()

        assertEquals(listOf(100, 50), requests().map { it.size })
        assertEquals(emptyList<Int>(), remaining())
        assertEquals(100L, dropped()[DropReason.REJECTED])
        assertNull(sender.millisUntilDue())
    }

    @Test
    fun `a failure after part of a split keeps only what the server did not answer for`() {
        var requests = 0
        server.dispatcher = answering { numbers ->
            requests++
            when {
                numbers.size > 2 -> 413
                requests == 2 -> 202
                else -> 500
            }
        }
        storeEvents(0..3)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(0, 1), listOf(2, 3)), requests())
        assertEquals(listOf(2, 3), remaining())
    }

    // Found in review

    @Test
    fun `a Retry-After shorter than the back-off waits for the back-off`() {
        storeEvents(0..0)
        answerRetryAfter(503, "0")
        val sender = sender(LowestRandom).apply { afterStart() }

        sender.runIfDue()

        assertEquals(2_500L, sender.millisUntilDue())
    }

    @Test
    fun `the app going to the background forces one attempt per series of retries, not one per callback`() {
        storeEvents(0..0)
        answer(500, 500)
        val sender = sender(HighestRandom).apply { afterStart() }
        sender.runIfDue()

        sender.afterSync()
        sender.runIfDue()
        assertEquals(2, requests().size)
        assertEquals(10_000L, sender.millisUntilDue())

        // Android reports the app's UI hidden, then further trim levels, each through the same sync.
        sender.afterSync()
        sender.runIfDue()

        assertEquals(0, requests().size)
        assertEquals(10_000L, sender.millisUntilDue())
    }

    @Test
    fun `a batch that got through ends the series for the batches after it in the same pass`() {
        storeEvents(0..149)
        answer(500, 202, 500)
        val sender = sender(HighestRandom).apply { afterStart() }
        sender.runIfDue()

        sender.runWhenDue()

        assertEquals(listOf(100, 100, 50), requests().map { it.size })
        assertEquals(5_000L, sender.millisUntilDue())
    }

    @Test
    fun `stored payloads that are not one JSON object each are dropped and counted, and the others are sent`() {
        storeEvents(0..0)
        for (bad in listOf("", "not json", "[1]", """{"n":9}{"n":10}""", """{"n":11}]}""")) {
            store.append(bad.encodeToByteArray(), EventClass.DIAGNOSTIC)
        }
        storeEvents(1..1)
        answer(202)

        sender().apply { afterStart() }.runIfDue()

        assertEquals(listOf(listOf(0, 1)), requests())
        assertEquals(emptyList<Int>(), remaining())
        assertEquals(5L, dropped()[DropReason.MALFORMED])
    }

    @Test
    fun `a full batch counts only events stored since the last pass`() {
        // A store whose count includes records it cannot hand out, such as expired ones a full disk cannot rewrite.
        val stuck = object : WriteOnlyStore() {
            var peeks = 0
            override val size: Int get() = 150
            override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> = emptyList<StoredEvent>().also { peeks++ }
            override fun append(payload: ByteArray, eventClass: EventClass) = Unit
            override fun sync() = Unit
            override fun countDropped(reason: DropReason, count: Long) = Unit
        }
        val sender = BatchSender(stuck, { error("nothing to send") }, BatchBody(endpoint, session), time, Random(seed = 7))
        sender.afterStart()
        sender.runIfDue()

        repeat(3) {
            sender.afterAppend()
            sender.runIfDue()
        }

        assertEquals(1, stuck.peeks)
        assertEquals(60_000L, sender.millisUntilDue())
    }

    private fun answering(status: (List<Int>) -> Int) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setResponseCode(status(numbersIn(request.body.clone().readUtf8())))
    }

    /** Monotonic time that moves only when the test moves it. */
    private class ManualTime : MonotonicClock {
        var now = 1_000_000L
        override fun elapsedMillis(): Long = now
    }

    /** Every random share of a wait is zero. */
    private object LowestRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = 0
    }

    /** Every random share of a wait is as long as it can be. */
    private object HighestRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }

    private companion object {
        fun numbersIn(body: String): List<Int> {
            val events = JSONObject(body).getJSONArray("events")
            return List(events.length()) { events.getJSONObject(it).getInt("n") }
        }
    }
}
