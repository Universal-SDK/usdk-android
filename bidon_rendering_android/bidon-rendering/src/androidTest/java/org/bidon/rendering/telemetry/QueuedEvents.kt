package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Telemetry as production runs it, its thread, gate and disk store, with every event the store takes also handed to
 * the test. A test therefore sees exactly what reached the device's queue, after sampling, the rate limit and
 * encoding on the telemetry thread.
 */
internal class QueuedEvents(directory: File) {

    /** One event as it was stored. */
    class Stored(val eventClass: EventClass, val json: JSONObject) {
        val name: String get() = json.getString("event_name")
    }

    private val stored = LinkedBlockingQueue<Stored>()
    private val failures = LinkedBlockingQueue<Throwable>()
    private val sessionId = SessionId.random()

    private val telemetry: Telemetry = BackgroundTelemetry.start(
        openStore = { Recording(DiskEventStore.open(directory)) },
        onFailure = { failures.add(it) },
        gate = TelemetryGate(sessionId, RateLimiter(MonotonicClock.System)),
    )

    /** The events of one ad, labelled as the adapter labels an interstitial. */
    fun adEvents(creative: Creative, adType: AdType = AdType.INTERSTITIAL): AdEvents =
        AdEvents(telemetry, sessionId, AUCTION_ID, adType, creative, Clock.System, MonotonicClock.System)

    /** The next event stored; fails when none is within [seconds]. */
    fun next(seconds: Long = 10): Stored {
        val event = stored.poll(seconds, TimeUnit.SECONDS)
        assertNull("telemetry failed", failures.peek())
        return event ?: throw AssertionError("no event stored within $seconds s")
    }

    /** Fails when another event is stored within [millis]. */
    fun assertNoMore(millis: Long = 1_000) {
        val extra = stored.poll(millis, TimeUnit.MILLISECONDS)
        assertNull("unexpected event ${extra?.json}", extra)
        assertNull("telemetry failed", failures.peek())
    }

    /** Asserts [event] is [name] of [eventClass], for this test's ad of [creative] and [adType]. */
    fun assertAdEvent(
        event: Stored,
        name: String,
        eventClass: EventClass,
        creative: Creative,
        adType: AdType = AdType.INTERSTITIAL,
    ) {
        assertEquals(name, event.name)
        assertEquals(eventClass, event.eventClass)
        assertEquals(AUCTION_ID, event.json.getString("auction_id"))
        assertEquals(adType.wireName, event.json.getString("ad_type"))
        assertEquals(sessionId.value, event.json.getString("session_id"))
        assertEquals(creative.wireName, event.json.getString("creative_type"))
    }

    private inner class Recording(private val store: EventStore) : EventStore by store {
        override fun append(payload: ByteArray, eventClass: EventClass) {
            store.append(payload, eventClass)
            stored.add(Stored(eventClass, JSONObject(payload.toString(Charsets.UTF_8))))
        }
    }

    companion object {
        const val AUCTION_ID = "auction-on-device"
    }
}
