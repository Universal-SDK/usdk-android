package org.bidon.rendering

import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.ProcessExit
import org.bidon.rendering.telemetry.SamplingRate
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.telemetry.TelemetryEvent
import org.bidon.rendering.telemetry.WireIntegration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/** The glue that labels an ad's events with what the host told `createRenderer`. */
class RendererTelemetryAdEventsTest {

    private class Captured : Telemetry {
        val events = mutableListOf<JSONObject>()
        override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
            events += JSONObject(capture(SamplingRate.ALL).encode().toString(Charsets.UTF_8))
        }
        override fun sync() = Unit
    }

    /** Every test starts as a process whose last renderer named no integration, whatever ran before it. */
    @Before
    fun forgetTheLastIntegration() {
        RendererTelemetry.adEvents(Captured(), CreativeType.HTML, ad = null, AdRenderingConfig())
    }

    @Test
    fun withTelemetryOffThereAreNoEventsToMake() {
        assertNull(RendererTelemetry.adEvents(Telemetry.Disabled, CreativeType.HTML, AdInfo("auction", AdType.BANNER), AdRenderingConfig()))
    }

    @Test
    fun aRendererCreatedWithTelemetryOffLeavesNoIntegrationBehind() {
        val events = RendererTelemetry.adEvents(
            Telemetry.Disabled,
            CreativeType.HTML,
            AdInfo("auction", AdType.BANNER, Integration.MAX_BIDDING),
            AdRenderingConfig(),
        )

        assertNull(events)
        assertNull(RendererTelemetry.lastIntegration)
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

    @Test
    fun everyIntegrationIsWrittenAsItsTwoLabels() {
        val wire = mapOf(
            Integration.DIRECT to ("publisher" to "bidon"),
            Integration.MAX_CUSTOM to ("max_adapter" to "bidon"),
            Integration.MAX_BIDDING to ("max_adapter" to "max"),
        )
        for ((integration, labels) in wire) {
            val telemetry = Captured()
            RendererTelemetry.adEvents(
                telemetry, CreativeType.HTML, AdInfo("auction", AdType.BANNER, integration), AdRenderingConfig(),
            )!!.rendered()

            val envelope = telemetry.events.single().getJSONObject("envelope")
            assertEquals("$integration", labels, envelope.getString("host") to envelope.getString("auction_owner"))
        }
    }

    @Test
    fun anAdWithNoIntegrationCarriesNoLabelsButStillTheUptime() {
        val telemetry = Captured()
        RendererTelemetry.adEvents(
            telemetry, CreativeType.HTML, AdInfo("auction", AdType.BANNER, integration = null), AdRenderingConfig(),
        )!!.rendered()

        val envelope = telemetry.events.single().getJSONObject("envelope")
        assertFalse(envelope.has("host"))
        assertFalse(envelope.has("auction_owner"))
        // Telemetry never starts in these tests, so the uptime is the placeholder that reads zero until it does.
        assertEquals(0L, envelope.getLong("sdk_uptime_ms"))
    }

    @Test
    fun theLastRendererCreatedLabelsWhatBelongsToNoAd() {
        RendererTelemetry.adEvents(
            Captured(), CreativeType.HTML, AdInfo("a", AdType.BANNER, Integration.MAX_CUSTOM), AdRenderingConfig(),
        )
        assertEquals(
            WireIntegration.MAX_CUSTOM,
            RendererTelemetry.lastIntegration,
        )

        RendererTelemetry.adEvents(
            Captured(), CreativeType.VAST, AdInfo("b", AdType.REWARDED, Integration.MAX_BIDDING), AdRenderingConfig(),
        )
        assertEquals(
            WireIntegration.MAX_BIDDING,
            RendererTelemetry.lastIntegration,
        )
    }

    @Test
    fun aRendererCreatedWithoutAnIntegrationClearsTheLabelOfTheOneBefore() {
        RendererTelemetry.adEvents(
            Captured(), CreativeType.HTML, AdInfo("a", AdType.BANNER, Integration.DIRECT), AdRenderingConfig(),
        )

        RendererTelemetry.adEvents(Captured(), CreativeType.HTML, ad = null, AdRenderingConfig())
        assertNull(RendererTelemetry.lastIntegration)

        RendererTelemetry.adEvents(
            Captured(), CreativeType.HTML, AdInfo("a", AdType.BANNER, Integration.DIRECT), AdRenderingConfig(),
        )
        RendererTelemetry.adEvents(
            Captured(), CreativeType.HTML, AdInfo("a", AdType.BANNER), AdRenderingConfig(),
        )
        assertNull(RendererTelemetry.lastIntegration)
    }

    /**
     * A process that has created no renderer labels a crash with no integration. The object is loaded again, in a
     * class loader of its own, because the other tests in this JVM have already created renderers through it.
     */
    @Test
    fun beforeAnyRendererIsCreatedThereIsNoIntegration() {
        val classPath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        // A class loader of its own gives a copy of RendererTelemetry that no other test has touched.
        URLClassLoader(classPath.toTypedArray(), ClassLoader.getSystemClassLoader().parent).use { loader ->
            val type = loader.loadClass(RendererTelemetry::class.java.name)
            assertNotSame(RendererTelemetry::class.java, type)
            val fresh = type.getField("INSTANCE").get(null)

            assertNull(type.getMethod("getLastIntegration").invoke(fresh))
        }
    }
}
