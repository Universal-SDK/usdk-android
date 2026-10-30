package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.egyptianHieroglyphs
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * The rules of the BID-74 spec that hold for every value: the auction id (rule 5), the render duration (rule 10) and the
 * failure code (rule 11), and, for BID-136 and BID-140, that no exception message leaves the device (AC 5, AC 3).
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
    ) = AdEvents(telemetry, session, auctionId, AdType.BANNER, Renderer.HTML, null, { 0L }, monotonicClock)

    // rule 5

    @Test
    fun `the auction id is written exactly as given when it is not blank, and left out otherwise`() {
        runBlocking {
            checkAll(anyText.orNull()) { auctionId ->
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, auctionId)

                events.rendered()
                events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
                events.processGone(ProcessExit(didCrash = true, priorityAtExit = null))

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

    // rule 11, BID-136 AC 1 and AC 2

    @Test
    fun `any error code goes out as its number in a string with its domain, and an external code as the text it was`() {
        runBlocking {
            checkAll(Arb.enum<ErrorCode>(), Arb.int().orNull()) { code, externalCode ->
                val telemetry = RecordingTelemetry()
                adEvents(telemetry).loadFailed(code, externalCode?.toString())

                val json = telemetry.single("ad_load_failed")
                assertEquals(code.number.toString(), json.get("error_code"))
                assertEquals(code.domain.wireName, json.getString("error_domain"))
                if (externalCode != null && code.externalSource != null) {
                    assertEquals(externalCode.toString(), json.get("external_error_code"))
                    assertEquals(code.externalSource.wireName, json.getString("external_error_source"))
                } else {
                    assertFalse(json.has("external_error_code"))
                    assertFalse(json.has("external_error_source"))
                }
            }
        }
    }

    // BID-136 AC 5: what an exception says never leaves the device

    @Test
    fun `no message, URL or host of an exception chain is written, and its class names are`() {
        runBlocking {
            checkAll(Arb.list(Arb.enum<Failure>(), 1..5), Arb.list(leak, 5..5), Arb.enum<ErrorCode>()) { kinds, leaks, code ->
                val telemetry = RecordingTelemetry()
                val chain = kinds.foldRightIndexed<Failure, Throwable?>(null) { index, kind, cause ->
                    kind.create(leaks[index].message, cause)
                }!!

                adEvents(telemetry).loadFailed(code, cause = chain)

                val text = telemetry.emitted.single().event!!.encode().toString(Charsets.UTF_8)
                kinds.indices.forEach { index ->
                    val leaked = leaks[index]
                    listOf(
                        leaked.url, JSONObject.quote(leaked.url).trim('"'), leaked.host, leaked.host.take(4), leaked.text,
                        leaked.message, MESSAGE_PREFIX,
                    ).forEach { assertFalse("<$it> in $text", it in text) }
                }
                assertTrue(text, kinds.first().className in text)
                assertTrue(text, kinds.last().className in text)
            }
        }
    }

    @Test
    fun `any show failure goes out as its number in a string with its domain and show stage, and an external code as the text it was`() {
        runBlocking {
            checkAll(Arb.element(SHOW_CODES), Arb.int().orNull()) { code, externalCode ->
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry)
                events.showRequested()

                events.showFailed(code, externalCode?.toString())

                val json = telemetry.single("ad_show_failed")
                assertEquals(code.number.toString(), json.get("error_code"))
                assertEquals(code.domain.wireName, json.getString("error_domain"))
                assertEquals(code.showStage?.wireName, json.getString("failed_stage"))
                if (externalCode != null && code.externalSource != null) {
                    assertEquals(externalCode.toString(), json.get("external_error_code"))
                    assertEquals(code.externalSource.wireName, json.getString("external_error_source"))
                } else {
                    assertFalse(json.has("external_error_code"))
                    assertFalse(json.has("external_error_source"))
                }
            }
        }
    }

    @Test
    fun `no message, URL or host of an exception chain is written by a show failure, and its class names are`() {
        runBlocking {
            checkAll(Arb.list(Arb.enum<Failure>(), 1..5), Arb.list(leak, 5..5), Arb.element(SHOW_CODES)) { kinds, leaks, code ->
                val telemetry = RecordingTelemetry()
                val chain = kinds.foldRightIndexed<Failure, Throwable?>(null) { index, kind, cause ->
                    kind.create(leaks[index].message, cause)
                }!!
                val events = adEvents(telemetry)
                events.showRequested()

                events.showFailed(code, cause = chain)

                val text = telemetry.emitted.single { it.name == "ad_show_failed" }.event!!.encode().toString(Charsets.UTF_8)
                kinds.indices.forEach { index ->
                    val leaked = leaks[index]
                    listOf(
                        leaked.url, JSONObject.quote(leaked.url).trim('"'), leaked.host, leaked.host.take(4), leaked.text,
                        leaked.message, MESSAGE_PREFIX,
                    ).forEach { assertFalse("<$it> in $text", it in text) }
                }
                assertTrue(text, kinds.first().className in text)
                assertTrue(text, kinds.last().className in text)
            }
        }
    }

    @Test
    fun `the time since the load ended is the show minus the load end when the show is not earlier, and left out otherwise`() {
        runBlocking {
            checkAll(reading, reading, Arb.boolean()) { loaded, shown, hasLoad ->
                var now = 0L
                val telemetry = RecordingTelemetry()
                val events = adEvents(telemetry, monotonicClock = { now })

                if (hasLoad) {
                    now = loaded
                    events.loadSucceeded()
                }
                now = shown
                events.showRequested()

                val json = telemetry.single("ad_show_requested")
                if (hasLoad && shown >= loaded) {
                    assertJsonInteger(shown - loaded, json, "time_since_loaded_ms")
                } else {
                    assertFalse("loaded=$loaded shown=$shown hasLoad=$hasLoad", json.has("time_since_loaded_ms"))
                }
            }
        }
    }

    /**
     * The text an exception message can hold, with markers no class name or field name contains: `qx` leads every
     * host and `zq9` every text.
     */
    private class Leak(val url: String, val host: String, val text: String) {
        val message: String get() = "$MESSAGE_PREFIX $url on $host: $text"
    }

    private val leak: Arb<Leak> = Arb.bind(
        Arb.string(3..12, Codepoint.az()),
        Arb.string(1..30, Codepoint.alphanumeric()),
        Arb.string(1..30, Codepoint.alphanumeric()),
        Arb.string(0..10, Codepoint.cyrillic()),
    ) { domain, path, text, nonAscii ->
        Leak(url = "https://qx$domain.example/$path?token=$text", host = "qx$domain.example", text = "zq9$text$nonAscii")
    }

    private enum class Failure(val className: String, private val make: (String, Throwable?) -> Throwable) {
        STATE("java.lang.IllegalStateException", { message, cause -> IllegalStateException(message, cause) }),
        ARGUMENT("java.lang.IllegalArgumentException", { message, cause -> IllegalArgumentException(message, cause) }),
        IO("java.io.IOException", { message, cause -> IOException(message, cause) }),
        CONNECT("java.net.ConnectException", { message, cause -> ConnectException(message).apply { initCause(cause) } }),
        HOST("java.net.UnknownHostException", { message, cause -> UnknownHostException(message).apply { initCause(cause) } });

        fun create(message: String, cause: Throwable?): Throwable = make(message, cause)
    }

    private companion object {
        const val MESSAGE_PREFIX = "failed to load"
    }
}
