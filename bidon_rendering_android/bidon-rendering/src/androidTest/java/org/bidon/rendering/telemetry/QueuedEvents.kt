package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val name: String get() = json.getJSONObject("envelope").getString("event_name")
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
    fun adEvents(renderer: Renderer, adType: AdType = AdType.INTERSTITIAL): AdEvents =
        AdEvents(telemetry, sessionId, AUCTION_ID, adType, renderer, declaredType = null, Clock.System, MonotonicClock.System)

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

    /**
     * Asserts [event] is the impression of this test's ad of [adType]: an `ad_impression` of class COUNTED, kept at
     * rate 1, without a renderer.
     */
    fun assertImpression(event: Stored, adType: AdType = AdType.INTERSTITIAL) {
        assertAdEvent(event, "ad_impression", EventClass.COUNTED, renderer = null, adType)
        assertEquals(1.0, event.json.getJSONObject("envelope").getDouble("sampling_rate"), 0.0)
        assertEquals(false, event.json.get("om_enabled"))
    }

    /**
     * Asserts [event] is the `ad_show_requested` of this test's ad of [adType]: a DIAGNOSTIC event without a renderer,
     * with a time since the load ended when [afterLoad] is `true` and none when it is `false`.
     */
    fun assertShowRequested(event: Stored, afterLoad: Boolean = true, adType: AdType = AdType.INTERSTITIAL) {
        assertAdEvent(event, "ad_show_requested", EventClass.DIAGNOSTIC, renderer = null, adType)
        if (afterLoad) {
            assertTrue("no time since loaded in ${event.json}", event.json.optLong("time_since_loaded_ms", -1) >= 0)
        } else {
            assertFalse("time since loaded in ${event.json}", event.json.has("time_since_loaded_ms"))
        }
    }

    /** Asserts [event] carries a render duration. */
    fun assertRenderDuration(event: Stored) {
        assertTrue("no render duration in ${event.json}", event.json.optLong("render_duration_ms", -1) >= 0)
    }

    /**
     * Asserts [event] is [name] of [eventClass], for this test's ad of [adType], whose renderer is [renderer], or
     * which names none when [renderer] is `null`. No event carries a `creative_type`.
     */
    fun assertAdEvent(
        event: Stored,
        name: String,
        eventClass: EventClass,
        renderer: Renderer?,
        adType: AdType = AdType.INTERSTITIAL,
    ) {
        assertEquals(name, event.name)
        assertEquals(eventClass, event.eventClass)
        assertEquals(AUCTION_ID, event.json.getJSONObject("envelope").getString("auction_id"))
        assertEquals(adType.wireName, event.json.getJSONObject("envelope").getString("ad_type"))
        assertEquals(sessionId.value, event.json.getJSONObject("envelope").getString("session_id"))
        assertFalse("creative_type on $name", event.json.has("creative_type"))
        if (renderer == null) {
            assertFalse("renderer on $name", event.json.has("renderer"))
        } else {
            assertEquals(renderer.wireName, event.json.getString("renderer"))
        }
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
