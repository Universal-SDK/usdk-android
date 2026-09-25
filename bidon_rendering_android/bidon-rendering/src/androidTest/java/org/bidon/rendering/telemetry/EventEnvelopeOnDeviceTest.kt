package org.bidon.rendering.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The unit tests encode with the JVM's org.json. The app encodes with the one in the Android framework, which escapes
 * and formats numbers in its own way, so the round trip is checked again here against that implementation.
 */
@RunWith(AndroidJUnit4::class)
class EventEnvelopeOnDeviceTest {

    @Test
    fun anEnvelopeReadsBackAsCapturedWithTheFrameworkJson() {
        val awkward = "quote \" backslash \\ slash / newline \n tab \t nul \u0000 cyrillic аукціон surrogate 𓀀"
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val envelope = EventEnvelope.capture(
                eventName = awkward,
                sessionId = SessionId(awkward),
                clock = { 1_790_326_698_635L },
                auctionId = awkward,
                adType = AdType.INTERSTITIAL,
                adFormat = awkward,
                samplingRate = SamplingRate(0.1),
            )

            val json = JSONObject(envelope.encode().toString(Charsets.UTF_8))

            assertEquals(envelope.eventId, json.getString("event_id"))
            assertEquals(awkward, json.getString("event_name"))
            assertEquals(1_790_326_698_635L, json.getLong("event_ts"))
            assertEquals(awkward, json.getString("auction_id"))
            assertEquals(awkward, json.getString("session_id"))
            assertEquals("interstitial", json.getString("ad_type"))
            assertEquals(awkward, json.getString("ad_format"))
            assertEquals(0.1, json.getDouble("sampling_rate"), 0.0)
            assertEquals(8, json.length())
        } finally {
            Locale.setDefault(original)
        }
    }
}
