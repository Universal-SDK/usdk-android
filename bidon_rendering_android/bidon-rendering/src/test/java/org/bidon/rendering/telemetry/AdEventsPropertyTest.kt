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
 * The rules of the BID-74 spec that hold for every value: the auction id (rule 5), the load time (rule 10) and the
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
    fun `the load time is the first end minus the first start, when the end is not earlier`() {
        runBlocking {
            checkAll(reading, reading, reading, reading) { start, laterStart, end, laterEnd ->
                var now = 0L
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, monotonicClock = { now })

                now = start
                events.loadStarted()
                now = laterStart
                events.loadStarted()
                now = end
                events.loaded()
                now = laterEnd
                events.loaded()
                events.rendered()

                val json = telemetry.single("creative_rendered")
                if (end >= start) {
                    assertJsonInteger(end - start, json, "load_ms")
                } else {
                    assertFalse("$start..$end", json.has("load_ms"))
                }
            }
        }
    }

    @Test
    fun `there is a load time only when both ends of the load were seen in order before the render`() {
        runBlocking {
            checkAll(Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.long(0L, 1L shl 40)) { started, ended, endFirst, gap ->
                var now = 0L
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, monotonicClock = { now })

                val calls = listOfNotNull(
                    events::loadStarted.takeIf { started },
                    events::loaded.takeIf { ended },
                ).let { if (endFirst) it.reversed() else it }
                calls.forEach { call ->
                    call()
                    now += gap
                }
                events.rendered()

                val json = telemetry.single("creative_rendered")
                if (started && ended && !endFirst) {
                    assertJsonInteger(gap, json, "load_ms")
                } else {
                    assertFalse("started=$started ended=$ended endFirst=$endFirst", json.has("load_ms"))
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
