package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The rules of the BID-74 spec that hold for every value: the auction id (rule 5), the render duration (rule 10) and the
 * failure code (rule 11).
 */
class AdEventsPropertyTest {

    private val session = SessionId.of("s")!!

    /** Text of every kind, plus strings made only of whitespace, so blank ids come up often. */
    private val anyText: Arb<String> = Arb.choice(
        Arb.string(0..40, Codepoint.ascii()),
        Arb.string(0..40, Codepoint.cyrillic()),
        Arb.string(0..40, Codepoint.egyptianHieroglyphs()),
        Arb.string(0..6, Arb.element(listOf(' ', '\t', '\n', '\r', ' ').map { Codepoint(it.code) })),
    )

    /** Readings far enough from the ends of `Long` that their difference cannot overflow. */
    private val reading = Arb.long(-1L shl 60, 1L shl 60)

    private fun adEvents(
        telemetry: Telemetry,
        auctionId: String? = "a",
        monotonicClock: MonotonicClock = MonotonicClock { 0L },
    ) = AdEvents(telemetry, session, auctionId, AdType.BANNER, Creative.HTML, { 0L }, monotonicClock)

    // rule 5

    @Test
    fun `the auction id is written exactly as given when it is not blank, and left out otherwise`() {
        runBlocking {
            checkAll(anyText.orNull()) { auctionId ->
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, auctionId)

                events.rendered()
                events.loadFailed(LoadFailure.WEBVIEW_ERROR)
                events.processGone(didCrash = true)

                assertEquals(3, telemetry.emitted.size)
                telemetry.emitted.forEach { emit ->
                    val json = emit.json().getJSONObject("envelope")
                    if (auctionId != null && auctionId.isNotBlank()) {
                        assertEquals(auctionId, json.getString("auction_id"))
                    } else {
                        assertFalse("<$auctionId>", json.has("auction_id"))
                    }
                }
            }
        }
    }

    // rule 10

    @Test
    fun `the render duration is the render minus the first mark, the show mark before the view mark, when the render is not earlier`() {
        runBlocking {
            checkAll(reading, reading, reading, reading, reading, Arb.boolean(), Arb.boolean()) {
                first, second, third, fourth, render, hasShow, hasView ->
                var now = 0L
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, monotonicClock = { now })

                if (hasShow) {
                    now = first
                    events.showRequested()
                    now = second
                    events.showRequested()
                }
                if (hasView) {
                    now = third
                    events.viewRequested()
                    now = fourth
                    events.viewRequested()
                }
                now = render
                events.rendered()

                val json = telemetry.single("ad_impression")
                val mark = if (hasShow) first else if (hasView) third else null
                if (mark != null && render >= mark) {
                    assertJsonInteger(render - mark, json, "render_duration_ms")
                } else {
                    assertFalse("mark=$mark render=$render", json.has("render_duration_ms"))
                }
            }
        }
    }

    @Test
    fun `there is a render duration only when a mark was made before the render`() {
        runBlocking {
            checkAll(Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.long(0L, 1L shl 40)) { show, view, markFirst, gap ->
                var now = 0L
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, monotonicClock = { now })

                val marks = listOfNotNull(
                    events::showRequested.takeIf { show },
                    events::viewRequested.takeIf { view },
                )
                if (markFirst) {
                    marks.forEach { it() }
                    now += gap
                    events.rendered()
                } else {
                    events.rendered()
                    now += gap
                    marks.forEach { it() }
                }

                val json = telemetry.single("ad_impression")
                if ((show || view) && markFirst) {
                    assertJsonInteger(gap, json, "render_duration_ms")
                } else {
                    assertFalse("show=$show view=$view markFirst=$markFirst", json.has("render_duration_ms"))
                }
            }
        }
    }

    // rule 11

    @Test
    fun `any code is written as that integer, and no code is left out`() {
        runBlocking {
            checkAll(Arb.enum<LoadFailure>(), Arb.int().orNull()) { reason, code ->
                val telemetry = RecordingTelemetry()

                adEvents(telemetry).loadFailed(reason, code)

                val json = telemetry.single("creative_load_failed")
                assertEquals(reason.wireName, json.getString("reason"))
                if (code != null) {
                    assertJsonInteger(code.toLong(), json, "code")
                } else {
                    assertFalse(json.has("code"))
                }
            }
        }
    }
}
