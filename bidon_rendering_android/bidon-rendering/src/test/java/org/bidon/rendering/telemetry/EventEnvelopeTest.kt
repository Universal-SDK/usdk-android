package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

class EventEnvelopeTest {

    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!

    private var now = 1_790_326_698_635L
    private val clock = Clock { now }

    private var uptimeNow = 5_000L
    private val uptime = UptimeClock { uptimeNow }

    private fun EventEnvelope.event() = JSONObject(encode().toString(Charsets.UTF_8))

    private fun EventEnvelope.json() = event().getJSONObject("envelope")

    /** The ingest handler reads an event's name from `events[].envelope`; an event without one is not counted. */
    @Test
    fun `the envelope goes out as an object of its own, with nothing next to it`() {
        val event = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL).event()

        assertEquals(setOf("envelope"), event.keyNames())
    }

    @Test
    fun `every field goes out under the name the ingest contract gives it`() {
        val envelope = EventEnvelope.capture(
            eventName = "ad_filled",
            sessionId = session,
            clock = clock,
            uptime = uptime,
            auctionId = "b622d42a-cab5-498f-9582-39e7f9da09e9",
            adType = AdType.BANNER,
            adFormat = "MREC",
            integration = WireIntegration.MAX_BIDDING,
            samplingRate = SamplingRate.of(0.25)!!,
        )

        val json = envelope.json()

        assertEquals(
            setOf(
                "event_id", "event_name", "event_ts", "auction_id", "session_id", "ad_type", "ad_format",
                "host", "auction_owner", "sdk_uptime_ms", "sampling_rate",
            ),
            json.keyNames(),
        )
        assertEquals(envelope.eventId, json.getString("event_id"))
        assertEquals("ad_filled", json.getString("event_name"))
        assertEquals(1_790_326_698_635L, json.getLong("event_ts"))
        assertEquals("b622d42a-cab5-498f-9582-39e7f9da09e9", json.getString("auction_id"))
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.getString("session_id"))
        assertEquals("banner", json.getString("ad_type"))
        assertEquals("MREC", json.getString("ad_format"))
        assertEquals("max_adapter", json.getString("host"))
        assertEquals("max", json.getString("auction_owner"))
        assertEquals(0.25, json.getDouble("sampling_rate"), 0.0)
    }

    @Test
    fun `absent fields are left out rather than written as null`() {
        val json = EventEnvelope.capture("session_started", session, clock, uptime, samplingRate = SamplingRate.ALL).json()

        assertEquals(
            setOf("event_id", "event_name", "event_ts", "session_id", "sdk_uptime_ms", "sampling_rate"),
            json.keyNames(),
        )
    }

    // BID-123

    @Test
    fun `every integration goes out as the two PRD labels, in lower case`() {
        val wire = mapOf(
            WireIntegration.DIRECT to ("publisher" to "bidon"),
            WireIntegration.MAX_CUSTOM to ("max_adapter" to "bidon"),
            WireIntegration.MAX_BIDDING to ("max_adapter" to "max"),
        )

        wire.forEach { (integration, expected) ->
            val json = EventEnvelope.capture(
                "ad_filled", session, clock, uptime, integration = integration, samplingRate = SamplingRate.ALL,
            ).json()

            assertEquals(expected, json.getString("host") to json.getString("auction_owner"))
        }
    }

    @Test
    fun `the wire spelling of each label is exactly the contract's`() {
        assertEquals(
            listOf("publisher" to "bidon", "max_adapter" to "bidon", "max_adapter" to "max"),
            WireIntegration.entries.map { it.host to it.auctionOwner },
        )
    }

    @Test
    fun `the envelope never names an integration mode or a schema version`() {
        val keys = EventEnvelope.capture(
            "ad_filled", session, clock, uptime, integration = WireIntegration.DIRECT, samplingRate = SamplingRate.ALL,
        ).json().keyNames()

        assertEquals(
            setOf(
                "event_id", "event_name", "event_ts", "session_id", "host", "auction_owner", "sdk_uptime_ms",
                "sampling_rate",
            ),
            keys,
        )
    }

    @Test
    fun `without an integration the labels are absent, not null, and the uptime is still there`() {
        val json = EventEnvelope.capture("ad_filled", session, clock, uptime, integration = null, samplingRate = SamplingRate.ALL)
            .json()

        assertFalse(json.has("host"))
        assertFalse(json.has("auction_owner"))
        assertTrue(json.has("sdk_uptime_ms"))
    }

    @Test
    fun `the uptime is the uptime clock's reading, as a whole number of milliseconds`() {
        uptimeNow = 123_456L
        val json = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL).json()

        assertJsonInteger(123_456L, json, "sdk_uptime_ms")
    }

    @Test
    fun `an uptime of zero is written as zero, not left out`() {
        uptimeNow = 0L
        val json = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL).json()

        assertJsonInteger(0L, json, "sdk_uptime_ms")
    }

    @Test
    fun `the uptime is the moment of capture, not of encoding`() {
        uptimeNow = 1_000L
        val envelope = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)
        uptimeNow = 5_000L

        assertJsonInteger(1_000L, envelope.json(), "sdk_uptime_ms")
        assertJsonInteger(1_000L, envelope.json(), "sdk_uptime_ms")
    }

    @Test
    fun `the uptime keeps counting when the wall clock goes back`() {
        uptimeNow = 1_000L
        val first = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)
        now -= 3_600_000
        uptimeNow = 2_000L
        val second = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)

        assertTrue(second.json().getLong("event_ts") < first.json().getLong("event_ts"))
        assertTrue(second.json().getLong("sdk_uptime_ms") > first.json().getLong("sdk_uptime_ms"))
    }

    @Test
    fun `every ad type goes out in the contract's spelling`() {
        val spelled = AdType.entries.map { type ->
            EventEnvelope.capture("ad_filled", session, clock, uptime, adType = type, samplingRate = SamplingRate.ALL)
                .json()
                .getString("ad_type")
        }

        assertEquals(listOf("banner", "interstitial", "rewarded"), spelled)
    }

    @Test
    fun `an event counts as kept at a rate of one unless told otherwise`() {
        val json = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL).json()

        assertEquals(1.0, json.getDouble("sampling_rate"), 0.0)
    }

    @Test
    fun `the timestamp is the moment of capture, not of encoding`() {
        val envelope = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)
        now += 60_000

        assertEquals(1_790_326_698_635L, envelope.json().getLong("event_ts"))
    }

    @Test
    fun `the event id is a version 4 UUID, new for every event`() {
        val ids = List(1_000) {
            EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL).eventId
        }

        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `encoding the same event twice gives the same bytes, so a retry cannot mint a second event`() {
        val envelope = EventEnvelope.capture(
            "ad_filled",
            session,
            clock,
            uptime,
            auctionId = "a",
            adType = AdType.REWARDED,
            samplingRate = SamplingRate.ALL,
        )

        assertArrayEquals(envelope.encode(), envelope.encode())
    }

    @Test
    fun `two events captured at the same instant are still two events`() {
        val first = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)
        val second = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.ALL)

        assertNotEquals(first.json().getString("event_id"), second.json().getString("event_id"))
    }

    @Test
    fun `numbers are written in ASCII digits whatever the device locale`() {
        val original = Locale.getDefault()
        try {
            // Eastern Arabic digits: the locale that once turned a VAST macro into "٥٠" (f259398).
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val text = EventEnvelope.capture("ad_filled", session, clock, uptime, samplingRate = SamplingRate.of(0.25)!!)
                .encode().toString(Charsets.UTF_8)

            assertTrue(text, text.contains("\"event_ts\":1790326698635"))
            assertTrue(text, text.contains("\"sampling_rate\":0.25"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `text is encoded as UTF-8`() {
        val envelope = EventEnvelope.capture(
            "ad_filled",
            SessionId.of("сесія-𓀀")!!,
            clock,
            uptime,
            auctionId = "аукціон",
            samplingRate = SamplingRate.ALL,
        )

        val json = envelope.json()

        assertEquals("сесія-𓀀", json.getString("session_id"))
        assertEquals("аукціон", json.getString("auction_id"))
    }

    @Test
    fun `a sampling rate is a fraction above zero and at most one, and anything else is no rate at all`() {
        listOf(0.0, -0.1, 1.000_000_1, 2.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { rate -> assertNull("$rate", SamplingRate.of(rate)) }
        listOf(Double.MIN_VALUE, 0.001, 0.5, 1.0).forEach { rate -> assertEquals(rate, SamplingRate.of(rate)?.fraction) }
    }

    @Test
    fun `a blank session id is no session id at all`() {
        listOf("", " ", "\t\n").forEach { blank -> assertNull(SessionId.of(blank)) }
        assertEquals("s", SessionId.of("s")?.value)
    }

    @Test
    fun `a minted session id is a random UUID, different every time`() {
        val minted = (1..100).map { SessionId.random().value }

        minted.forEach {
            assertEquals(it, UUID.fromString(it).toString())
            assertEquals(4, UUID.fromString(it).version())
        }
        assertEquals(minted.size, minted.toSet().size)
    }
}
