package org.bidon.rendering

import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.SamplingRate
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.telemetry.TelemetryEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The glue that labels an ad's events with what the host told `createRenderer`. */
class RendererTelemetryAdEventsTest {

    private class Captured : Telemetry {
        val events = mutableListOf<JSONObject>()
        override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
            events += JSONObject(capture(SamplingRate.ALL).encode().toString(Charsets.UTF_8))
        }
        override fun sync() = Unit
    }

    @Test
    fun withTelemetryOffThereAreNoEventsToMake() {
        assertNull(RendererTelemetry.adEvents(Telemetry.Disabled, CreativeType.HTML, AdInfo("auction", AdType.BANNER)))
    }

    @Test
    fun everyCreativeTypeAndAdTypeIsWrittenAsTheHostNamedIt() {
        val creatives = mapOf(CreativeType.HTML to "html", CreativeType.MRAID to "mraid", CreativeType.VAST to "vast")
        val adTypes = mapOf(AdType.BANNER to "banner", AdType.INTERSTITIAL to "interstitial", AdType.REWARDED to "rewarded")
        for ((creative, creativeWire) in creatives) {
            for ((adType, adTypeWire) in adTypes) {
                val telemetry = Captured()
                RendererTelemetry.adEvents(telemetry, creative, AdInfo("auction-$creativeWire", adType))!!
                    .processGone(didCrash = false)

                val event = telemetry.events.single()
                assertEquals(creativeWire, event.getString("creative_type"))
                assertEquals(adTypeWire, event.getJSONObject("envelope").getString("ad_type"))
                assertEquals("auction-$creativeWire", event.getJSONObject("envelope").getString("auction_id"))
            }
        }
    }

    @Test
    fun anAdTheHostDidNotLabelCarriesNoAuctionOrAdType() {
        val telemetry = Captured()
        RendererTelemetry.adEvents(telemetry, CreativeType.VAST, ad = null)!!.rendered()

        val event = telemetry.events.single()
        assertFalse(event.getJSONObject("envelope").has("auction_id"))
        assertFalse(event.getJSONObject("envelope").has("ad_type"))
        assertEquals(RendererTelemetry.sessionId.value, event.getJSONObject("envelope").getString("session_id"))
    }
}
