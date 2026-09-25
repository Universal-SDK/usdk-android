package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

class EventEnvelopeTest {

    private val session = SessionId("550e8400-e29b-41d4-a716-446655440000")

    private var now = 1_790_326_698_635L
    private val clock = Clock { now }

    private fun EventEnvelope.json() = JSONObject(encode().toString(Charsets.UTF_8))

    @Test
    fun `every field goes out under the name the ingest contract gives it`() {
        val envelope = EventEnvelope.capture(
            eventName = "ad_filled",
            sessionId = session,
            clock = clock,
            auctionId = "b622d42a-cab5-498f-9582-39e7f9da09e9",
            adType = AdType.BANNER,
            adFormat = "MREC",
            samplingRate = SamplingRate(0.25),
        )

        val json = envelope.json()

        assertEquals(
            setOf(
                "event_id", "event_name", "event_ts", "auction_id", "session_id", "ad_type", "ad_format",
                "sampling_rate",
            ),
            json.keySet(),
        )
        assertEquals(envelope.eventId, json.getString("event_id"))
        assertEquals("ad_filled", json.getString("event_name"))
        assertEquals(1_790_326_698_635L, json.getLong("event_ts"))
        assertEquals("b622d42a-cab5-498f-9582-39e7f9da09e9", json.getString("auction_id"))
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.getString("session_id"))
        assertEquals("banner", json.getString("ad_type"))
        assertEquals("MREC", json.getString("ad_format"))
        assertEquals(0.25, json.getDouble("sampling_rate"), 0.0)
    }

    @Test
    fun `absent fields are left out rather than written as null`() {
        val json = EventEnvelope.capture("session_started", session, clock).json()

        assertEquals(setOf("event_id", "event_name", "event_ts", "session_id", "sampling_rate"), json.keySet())
    }

    @Test
    fun `every ad type goes out in the contract's spelling`() {
        val spelled = AdType.entries.map { type ->
            EventEnvelope.capture("ad_filled", session, clock, adType = type).json().getString("ad_type")
        }

        assertEquals(listOf("banner", "interstitial", "rewarded"), spelled)
    }

    @Test
    fun `an event counts as kept at a rate of one unless told otherwise`() {
        val json = EventEnvelope.capture("ad_filled", session, clock).json()

        assertEquals(1.0, json.getDouble("sampling_rate"), 0.0)
    }

    @Test
    fun `the timestamp is the moment of capture, not of encoding`() {
        val envelope = EventEnvelope.capture("ad_filled", session, clock)
        now += 60_000

        assertEquals(1_790_326_698_635L, envelope.json().getLong("event_ts"))
    }

    @Test
    fun `the event id is a version 4 UUID, new for every event`() {
        val ids = List(1_000) { EventEnvelope.capture("ad_filled", session, clock).eventId }

        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `encoding the same event twice gives the same bytes, so a retry cannot mint a second event`() {
        val envelope = EventEnvelope.capture("ad_filled", session, clock, auctionId = "a", adType = AdType.REWARDED)

        assertArrayEquals(envelope.encode(), envelope.encode())
    }

    @Test
    fun `two events captured at the same instant are still two events`() {
        val first = EventEnvelope.capture("ad_filled", session, clock)
        val second = EventEnvelope.capture("ad_filled", session, clock)

        assertNotEquals(first.json().getString("event_id"), second.json().getString("event_id"))
    }

    @Test
    fun `numbers are written in ASCII digits whatever the device locale`() {
        val original = Locale.getDefault()
        try {
            // Eastern Arabic digits: the locale that once turned a VAST macro into "٥٠" (f259398).
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val text = EventEnvelope.capture("ad_filled", session, clock, samplingRate = SamplingRate(0.25))
                .encode().toString(Charsets.UTF_8)

            assertTrue(text, text.contains("\"event_ts\":1790326698635"))
            assertTrue(text, text.contains("\"sampling_rate\":0.25"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `text is encoded as UTF-8`() {
        val envelope = EventEnvelope.capture("ad_filled", SessionId("сесія-𓀀"), clock, auctionId = "аукціон")

        val json = envelope.json()

        assertEquals("сесія-𓀀", json.getString("session_id"))
        assertEquals("аукціон", json.getString("auction_id"))
    }

    @Test
    fun `a sampling rate is a fraction above zero and at most one`() {
        listOf(0.0, -0.1, 1.000_000_1, 2.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { rate ->
                assertThrows("$rate", IllegalArgumentException::class.java) { SamplingRate(rate) }
            }
        listOf(Double.MIN_VALUE, 0.001, 0.5, 1.0).forEach { rate -> assertEquals(rate, SamplingRate(rate).fraction, 0.0) }
    }

    @Test
    fun `a session id cannot be blank`() {
        listOf("", " ", "\t\n").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { SessionId(blank) }
        }
    }
}
