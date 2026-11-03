package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.ElapsedUptimeClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever a host or a creative puts into a field, the bytes read back as exactly what was captured. The strings mix
 * control characters, quotes and backslashes (ASCII), text outside Latin (Cyrillic), characters that need a
 * surrogate pair in UTF-16 (hieroglyphs), and a few thousand characters at a time.
 */
class EventEnvelopePropertyTest {

    private val anyText: Arb<String> = Arb.choice(
        Arb.string(0..40, Codepoint.ascii()),
        Arb.string(0..40, Codepoint.cyrillic()),
        Arb.string(0..40, Codepoint.egyptianHieroglyphs()),
        Arb.string(1_000..4_000, Codepoint.ascii()),
    )

    private val sessionId = anyText.filter { it.isNotBlank() }

    private val samplingRate = Arb.double(0.0, 1.0, includeNonFiniteEdgeCases = false).filter { it > 0.0 }

    @Test
    fun `an encoded envelope reads back as exactly what was captured`() {
        runBlocking {
            checkAll(
                anyText,
                sessionId,
                Arb.long(),
                anyText.orNull(),
                Arb.enum<AdType>().orNull(),
                anyText.orNull(),
                Arb.enum<WireIntegration>().orNull(),
                Arb.long(0L..Long.MAX_VALUE),
                samplingRate,
            ) { name, session, ts, auctionId, adType, adFormat, integration, uptimeMs, rate ->
                val envelope = EventEnvelope.capture(
                    eventName = name,
                    sessionId = SessionId.of(session)!!,
                    clock = { ts },
                    uptime = { uptimeMs },
                    auctionId = auctionId,
                    adType = adType,
                    adFormat = adFormat,
                    integration = integration,
                    samplingRate = SamplingRate.of(rate)!!,
                )

                val json = JSONObject(envelope.encode().toString(Charsets.UTF_8)).getJSONObject("envelope")

                assertEquals(envelope.eventId, json.getString("event_id"))
                assertEquals(name, json.getString("event_name"))
                assertEquals(ts, json.getLong("event_ts"))
                assertEquals(auctionId, json.optStringOrNull("auction_id"))
                assertEquals(session, json.getString("session_id"))
                assertEquals(adType?.wireName, json.optStringOrNull("ad_type"))
                assertEquals(adFormat, json.optStringOrNull("ad_format"))
                assertEquals(integration?.host, json.optStringOrNull("host"))
                assertEquals(integration?.auctionOwner, json.optStringOrNull("auction_owner"))
                assertEquals(uptimeMs, json.getLong("sdk_uptime_ms"))
                assertEquals(rate, json.getDouble("sampling_rate"), 0.0)
                assertEquals(
                    6 + listOfNotNull(auctionId, adType, adFormat).size + (if (integration != null) 2 else 0),
                    json.length(),
                )
            }
        }
    }

    /**
     * The device's own clock orders events whatever the wall clock does: across any sequence of captures with the wall
     * clock jumping forwards and backwards, `sdk_uptime_ms` never decreases and `event_ts` follows the wall clock.
     */
    @Test
    fun `the uptime never decreases while the wall clock jumps both ways`() {
        runBlocking {
            checkAll(
                Arb.long(0L..1_000_000L),
                Arb.long(0L..10_000_000_000L),
                Arb.list(Arb.pair(Arb.long(-86_400_000L..86_400_000L), Arb.int(0..60_000)), 1..50),
            ) { origin, startWall, steps ->
                var elapsed = origin
                var wall = startWall
                val uptime = ElapsedUptimeClock { elapsed }
                val captured = steps.map { (wallJump, passed) ->
                    wall += wallJump
                    elapsed += passed
                    val envelope = EventEnvelope.capture(
                        "ad_filled", SessionId.of("s")!!, { wall }, uptime, samplingRate = SamplingRate.ALL,
                    )
                    val json = JSONObject(envelope.encode().toString(Charsets.UTF_8)).getJSONObject("envelope")
                    assertEquals(wall, json.getLong("event_ts"))
                    json.getLong("sdk_uptime_ms")
                }

                assertEquals(captured.sorted(), captured)
                assertTrue(captured.all { it >= 0 })
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
}
