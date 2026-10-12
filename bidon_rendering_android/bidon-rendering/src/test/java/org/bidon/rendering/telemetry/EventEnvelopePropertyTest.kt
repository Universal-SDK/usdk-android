package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
                samplingRate,
            ) { name, session, ts, auctionId, adType, adFormat, rate ->
                val envelope = EventEnvelope.capture(
                    eventName = name,
                    sessionId = SessionId.of(session)!!,
                    clock = { ts },
                    auctionId = auctionId,
                    adType = adType,
                    adFormat = adFormat,
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
                assertEquals(rate, json.getDouble("sampling_rate"), 0.0)
                assertEquals(
                    5 + listOfNotNull(auctionId, adType, adFormat).size,
                    json.length(),
                )
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
}
