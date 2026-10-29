package org.bidon.rendering

import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.ProcessExit
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
        assertNull(RendererTelemetry.adEvents(Telemetry.Disabled, CreativeType.HTML, AdInfo("auction", AdType.BANNER), AdRenderingConfig()))
    }

    @Test
    fun everyCreativeTypeAndAdTypeIsWrittenAsTheHostNamedIt() {
        val creatives = mapOf(CreativeType.HTML to "html", CreativeType.MRAID to "mraid", CreativeType.VAST to "vast")
        val adTypes = mapOf(AdType.BANNER to "banner", AdType.INTERSTITIAL to "interstitial", AdType.REWARDED to "rewarded")
        for ((creative, creativeWire) in creatives) {
            for ((adType, adTypeWire) in adTypes) {
                val telemetry = Captured()
                RendererTelemetry.adEvents(telemetry, creative, AdInfo("auction-$creativeWire", adType), AdRenderingConfig())!!
                    .processGone(ProcessExit(didCrash = false, priorityAtExit = null))

                val event = telemetry.events.single()
                assertEquals(creativeWire, event.getString("renderer"))
                assertFalse(event.has("creative_type"))
                assertFalse(event.has("crtype"))
                assertEquals(adTypeWire, event.getJSONObject("envelope").getString("ad_type"))
                assertEquals("auction-$creativeWire", event.getJSONObject("envelope").getString("auction_id"))
            }
        }
    }

    @Test
    fun theCrtypeTheDspDeclaredIsWrittenAsItCame() {
        val declared = mapOf(
            """{ "creative": { "type": "native" } }""" to "native",
            """{ "creative": { "type": "static_image" } }""" to "static_image",
            """{ "creative": { "type": "mraid" } }""" to "mraid",
        )
        for ((json, declaredType) in declared) {
            val telemetry = Captured()
            RendererTelemetry.adEvents(
                telemetry,
                CreativeType.HTML,
                AdInfo("auction", AdType.BANNER),
                AdRenderingConfig.fromJson(JSONObject(json)),
            )!!.processGone(ProcessExit(didCrash = true, priorityAtExit = null))

            val event = telemetry.events.single()
            assertEquals(declaredType, event.getString("crtype"))
            assertEquals("html", event.getString("renderer"))
        }
    }

    @Test
    fun anAdWhoseDspDeclaredNothingKnownCarriesNoCrtype() {
        listOf(null, JSONObject("{}"), JSONObject("""{ "creative": { "type": "banner" } }""")).forEach { rendering ->
            val telemetry = Captured()
            RendererTelemetry.adEvents(
                telemetry,
                CreativeType.VAST,
                AdInfo("auction", AdType.BANNER),
                AdRenderingConfig.fromJson(rendering),
            )!!.processGone(ProcessExit(didCrash = false, priorityAtExit = null))

            assertFalse("$rendering", telemetry.events.single().has("crtype"))
        }
    }

    @Test
    fun anAdTheHostDidNotLabelCarriesNoAuctionOrAdType() {
        val telemetry = Captured()
        RendererTelemetry.adEvents(telemetry, CreativeType.VAST, ad = null, rendering = AdRenderingConfig())!!.rendered()

        val event = telemetry.events.single()
        assertFalse(event.getJSONObject("envelope").has("auction_id"))
        assertFalse(event.getJSONObject("envelope").has("ad_type"))
        assertEquals(RendererTelemetry.sessionId.value, event.getJSONObject("envelope").getString("session_id"))
    }
}
