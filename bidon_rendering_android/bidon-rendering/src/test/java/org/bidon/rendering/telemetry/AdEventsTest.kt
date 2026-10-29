package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.net.ConnectException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The renderer's events of one ad, against a telemetry that records every call and a pair of clocks the test moves by
 * hand. Each test names the spec rule it pins (BID-74 spec).
 */
class AdEventsTest {

    /** A call that blocks when it must not fails its test instead of hanging the run. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!

    @Volatile
    private var now = 1_790_326_698_635L
    private val clock = Clock { now }

    @Volatile
    private var elapsed = 10_000L
    private val monotonic = MonotonicClock { elapsed }

    private val telemetry = RecordingTelemetry()

    private val otherThreadFailures = CopyOnWriteArrayList<Throwable>()

    private fun adEvents(
        auctionId: String? = AUCTION,
        adType: AdType? = AdType.INTERSTITIAL,
        renderer: Renderer = Renderer.HTML,
        declaredType: String? = null,
        telemetry: Telemetry = this.telemetry,
    ) = AdEvents(telemetry, session, auctionId, adType, renderer, declaredType, clock, monotonic)

    private val crashed = ProcessExit(didCrash = true, priorityAtExit = null)
    private val killed = ProcessExit(didCrash = false, priorityAtExit = null)

    // rule 1

    @Test
    fun `rendered emits one ad_impression as a counted event`() {
        adEvents().rendered()

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `a load failure emits one ad_load_failed as an error`() {
        adEvents().loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(listOf("ad_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `a gone render process emits one render_crashed as an error`() {
        adEvents().processGone(crashed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    // rule 1, rule 21

    @Test
    fun `starting a load emits nothing and touches nothing else on telemetry`() {
        val events = adEvents()

        repeat(3) {
            events.loadStarted()
        }

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
        assertEquals(0, telemetry.syncs)
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `the three events emit once each, in the order they were called`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(ErrorCode.VAST_INVALID)
        events.processGone(killed)

        assertEquals(3, telemetry.captures)
        assertEquals(
            listOf(
                "ad_impression" to EventClass.COUNTED,
                "ad_load_failed" to EventClass.ERROR,
                "render_crashed" to EventClass.ERROR,
            ),
            telemetry.calls(),
        )
    }

    // rule 2

    @Test
    fun `every captured event carries the class it was emitted with`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(ErrorCode.PLAYER_ERROR)
        events.processGone(crashed)

        telemetry.emitted.forEach { emit ->
            assertEquals(emit.name, emit.eventClass, emit.event!!.eventClass)
        }
        assertEquals(
            listOf(EventClass.COUNTED, EventClass.ERROR, EventClass.ERROR),
            telemetry.emitted.map { it.event!!.eventClass },
        )
    }

    // rule 3, rule 8, rule 9, rule 10

    @Test
    fun `ad_impression is the envelope plus the render duration and the OM flag, and nothing else`() {
        val events = adEvents(renderer = Renderer.MRAID)
        elapsed = 5_000
        events.showRequested()
        elapsed = 6_234

        events.rendered()

        val json = telemetry.single("ad_impression")
        assertEquals(setOf("envelope", "render_duration_ms", "om_enabled"), json.keyNames())
        assertEnvelope(json.envelope, "ad_impression", rate = 1.0)
        assertJsonInteger(1_234L, json, "render_duration_ms")
        assertEquals(false, json.get("om_enabled"))
    }

    @Test
    fun `an event's own fields sit next to the envelope, not inside it`() {
        val events = adEvents(renderer = Renderer.MRAID)
        events.viewRequested()

        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        val impression = telemetry.single("ad_impression")
        assertTrue(impression.has("render_duration_ms"))
        assertFalse(impression.envelope.has("render_duration_ms"))
        assertFalse(impression.envelope.has("om_enabled"))
        val failed = telemetry.single("ad_load_failed")
        assertTrue(failed.has("error_code"))
        assertFalse(failed.envelope.has("error_code"))
        val gone = telemetry.single("render_crashed")
        assertEquals("mraid", gone.getString("renderer"))
        assertFalse(gone.envelope.has("renderer"))
        assertFalse(gone.envelope.has("was_shown"))
    }

    @Test
    fun `ad_load_failed with everything it can carry is the envelope plus the code, owner, stage, load time, external pair and exception classes, and nothing else`() {
        telemetry.rate = SamplingRate.of(0.5)!!
        val events = adEvents(renderer = Renderer.VAST)
        events.loadStarted()
        elapsed += 420

        events.loadFailed(
            ErrorCode.VAST_WRAPPER_HTTP_STATUS,
            "404",
            IllegalStateException("never sent", IOException("never sent either")),
        )

        val json = telemetry.single("ad_load_failed")
        assertEquals(
            setOf(
                "envelope", "error_code", "error_domain", "failed_stage", "duration_ms", "external_error_code",
                "external_error_source", "exception_class", "exception_cause_class",
            ),
            json.keyNames(),
        )
        assertEnvelope(json.envelope, "ad_load_failed", rate = 0.5)
        assertEquals("8002", json.get("error_code"))
        assertEquals("dsp", json.getString("error_domain"))
        assertEquals("asset_fetch", json.getString("failed_stage"))
        assertJsonInteger(420L, json, "duration_ms")
        assertEquals("404", json.get("external_error_code"))
        assertEquals("http", json.getString("external_error_source"))
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals("java.io.IOException", json.getString("exception_cause_class"))
    }

    @Test
    fun `ad_load_failed with only a code is the envelope plus the code, owner and stage`() {
        val events = adEvents(renderer = Renderer.HTML)

        events.loadFailed(ErrorCode.BLANK_CREATIVE)

        val json = telemetry.single("ad_load_failed")
        assertEquals(setOf("envelope", "error_code", "error_domain", "failed_stage"), json.keyNames())
        assertEquals("6003", json.get("error_code"))
        assertEquals("creative", json.getString("error_domain"))
        assertEquals("webview_load", json.getString("failed_stage"))
    }

    @Test
    fun `a failure after the show stage began has no load stage and no load time`() {
        val events = adEvents(renderer = Renderer.VAST)
        events.loadStarted()
        elapsed += 60_030

        events.loadFailed(ErrorCode.NO_PLAYABLE_MEDIA, "403")

        val json = telemetry.single("ad_load_failed")
        assertEquals(
            setOf("envelope", "error_code", "error_domain", "external_error_code", "external_error_source"),
            json.keyNames(),
        )
        assertEquals("7001", json.get("error_code"))
        assertEquals("creative", json.getString("error_domain"))
        assertEquals("403", json.get("external_error_code"))
        assertEquals("vast", json.getString("external_error_source"))
    }

    @Test
    fun `ad_load_failed does not carry the creative type, the old reason or the old code`() {
        val events = adEvents(renderer = Renderer.MRAID)

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR, "-2")

        val json = telemetry.single("ad_load_failed")
        listOf("creative_type", "reason", "code").forEach { assertFalse(it, json.has(it)) }
        assertFalse(json.envelope.has("creative_type"))
    }

    @Test
    fun `error_code is a JSON string and the numbers around it are JSON integers`() {
        val events = adEvents()
        events.loadStarted()
        elapsed += 7

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_NETWORK, "-2")

        val json = telemetry.single("ad_load_failed")
        assertTrue(json.get("error_code").toString(), json.get("error_code") is String)
        assertTrue(json.get("external_error_code").toString(), json.get("external_error_code") is String)
        assertEquals("-2", json.get("external_error_code"))
        assertEquals("webview", json.getString("external_error_source"))
        assertJsonInteger(7L, json, "duration_ms")
    }

    @Test
    fun `render_crashed is the envelope plus the renderer and was_shown, and nothing else, when nothing else is known`() {
        telemetry.rate = SamplingRate.of(0.125)!!
        val events = adEvents(renderer = Renderer.HTML)
        events.loadStarted()
        elapsed += 30

        events.processGone(ProcessExit(didCrash = null, priorityAtExit = null))

        val json = telemetry.single("render_crashed")
        assertEquals(setOf("envelope", "renderer", "was_shown"), json.keyNames())
        assertEnvelope(json.envelope, "render_crashed", rate = 0.125)
        assertEquals("html", json.getString("renderer"))
        assertEquals(false, json.get("was_shown"))
    }

    @Test
    fun `render_crashed with everything it can carry is the envelope plus the renderer, crtype, did_crash, priority and was_shown, for each renderer`() {
        Renderer.entries.forEach { renderer ->
            val recording = RecordingTelemetry()
            val events = adEvents(renderer = renderer, declaredType = "static_image", telemetry = recording)
            events.rendered()

            events.processGone(ProcessExit(didCrash = true, priorityAtExit = RendererPriority.BOUND))

            val json = recording.single("render_crashed")
            assertEquals(
                "$renderer",
                setOf("envelope", "renderer", "crtype", "did_crash", "renderer_priority_at_exit", "was_shown"),
                json.keyNames(),
            )
            assertEquals("$renderer", renderer.wireName, json.getString("renderer"))
            assertEquals("$renderer", "static_image", json.getString("crtype"))
            assertEquals("$renderer", true, json.get("did_crash"))
            assertEquals("$renderer", "bound", json.get("renderer_priority_at_exit"))
            assertEquals("$renderer", true, json.get("was_shown"))
            assertEnvelope(json.envelope, "render_crashed", rate = 1.0)
        }
    }

    @Test
    fun `render_crashed without a declared crtype leaves the key out for each renderer`() {
        Renderer.entries.forEach { renderer ->
            val recording = RecordingTelemetry()

            adEvents(renderer = renderer, declaredType = null, telemetry = recording)
                .processGone(ProcessExit(didCrash = false, priorityAtExit = RendererPriority.WAIVED))

            val json = recording.single("render_crashed")
            assertEquals(
                "$renderer",
                setOf("envelope", "renderer", "did_crash", "renderer_priority_at_exit", "was_shown"),
                json.keyNames(),
            )
            assertEquals("$renderer", renderer.wireName, json.getString("renderer"))
        }
    }

    @Test
    fun `render_crashed carries the crtype as it was declared, whatever the renderer`() {
        listOf("html", "mraid", "vast", "static_image", "native", "playable").forEach { declaredType ->
            val recording = RecordingTelemetry()

            adEvents(renderer = Renderer.HTML, declaredType = declaredType, telemetry = recording)
                .processGone(crashed)

            assertEquals(declaredType, recording.single("render_crashed").getString("crtype"))
            assertEquals("html", recording.single("render_crashed").getString("renderer"))
        }
    }

    @Test
    fun `render_crashed carries no creative_type, URL, message or description`() {
        Renderer.entries.forEach { renderer ->
            val recording = RecordingTelemetry()

            adEvents(renderer = renderer, declaredType = "vast", telemetry = recording)
                .processGone(ProcessExit(didCrash = true, priorityAtExit = RendererPriority.IMPORTANT))

            val json = recording.single("render_crashed")
            listOf("creative_type", "url", "message", "error_message", "description", "reason", "error_code")
                .forEach { assertFalse("$renderer $it", json.has(it)) }
            assertFalse("$renderer", json.envelope.has("creative_type"))
        }
    }

    // rule 3

    @Test
    fun `every event encodes as one UTF-8 JSON object and nothing after it`() {
        val auctionId = "аукціон-𓀀"
        val events = adEvents(auctionId = auctionId)

        events.rendered()
        events.loadFailed(ErrorCode.MARKUP_REJECTED)
        events.processGone(killed)

        assertEquals(3, telemetry.emitted.size)
        telemetry.emitted.forEach { emit ->
            val text = emit.event!!.encode().toString(Charsets.UTF_8)
            val tokener = JSONTokener(text)
            val json = tokener.nextValue()
            assertTrue(text, json is JSONObject)
            assertEquals(text, 0.toChar(), tokener.nextClean())
            assertEquals(auctionId, (json as JSONObject).envelope.getString("auction_id"))
        }
    }

    @Test
    fun `the sampling rate written is the rate telemetry kept the event at`() {
        listOf(SamplingRate.ALL, SamplingRate.of(0.01)!!, SamplingRate.of(0.75)!!).forEach { rate ->
            val recording = RecordingTelemetry().apply { this.rate = rate }
            val events = adEvents(telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit ->
                assertEquals("${emit.name} at $rate", rate.fraction, emit.json().envelope.getDouble("sampling_rate"), 0.0)
            }
        }
    }

    @Test
    fun `each event gets an event id of its own`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        val ids = telemetry.emitted.map { it.json().envelope.getString("event_id") }
        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
        assertEquals(3, ids.toSet().size)
    }

    // rule 4

    @Test
    fun `the timestamp is the clock reading when telemetry captures, not when the ad events were made`() {
        val events = adEvents()
        now = 2_000_000_000_000L
        telemetry.beforeCapture = { now += 7 }

        events.rendered()
        assertEquals(2_000_000_000_007L, telemetry.single("ad_impression").envelope.getLong("event_ts"))

        now = 2_100_000_000_000L
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        assertEquals(2_100_000_000_007L, telemetry.single("ad_load_failed").envelope.getLong("event_ts"))

        now = 2_200_000_000_000L
        events.processGone(killed)
        assertEquals(2_200_000_000_007L, telemetry.single("render_crashed").envelope.getLong("event_ts"))
    }

    @Test
    fun `a wall clock moved backwards is written as it reads`() {
        val events = adEvents()
        now = 5L

        events.rendered()

        assertEquals(5L, telemetry.single("ad_impression").envelope.getLong("event_ts"))
    }

    // rule 5, rule 25 (blank is Kotlin's isBlank)

    @Test
    fun `the auction id goes out exactly as given, surrounding spaces included`() {
        listOf(AUCTION, " a ", "\ta", "аукціон", "x".repeat(5_000)).forEach { auctionId ->
            val recording = RecordingTelemetry()
            val events = adEvents(auctionId = auctionId, telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit ->
                assertEquals("${emit.name} <$auctionId>", auctionId, emit.json().envelope.getString("auction_id"))
            }
        }
    }

    @Test
    fun `a missing or blank auction id is left out of every event`() {
        listOf(null, "", " ", "\t\n", " ".takeIf { it.isBlank() }, " ").forEach { auctionId ->
            val recording = RecordingTelemetry()
            val events = adEvents(auctionId = auctionId, telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit ->
                assertFalse("${emit.name} <$auctionId>", emit.json().envelope.has("auction_id"))
            }
        }
    }

    // rule 6

    @Test
    fun `the ad type goes out in its wire spelling`() {
        AdType.entries.forEach { adType ->
            val recording = RecordingTelemetry()
            val events = adEvents(adType = adType, telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit ->
                assertEquals("${emit.name} $adType", adType.wireName, emit.json().envelope.getString("ad_type"))
            }
        }
    }

    @Test
    fun `a missing ad type is left out of every event`() {
        val events = adEvents(adType = null)

        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        assertEquals(3, telemetry.emitted.size)
        telemetry.emitted.forEach { emit -> assertFalse(emit.name, emit.json().envelope.has("ad_type")) }
    }

    // rule 7

    @Test
    fun `no event carries an ad format, banners included`() {
        AdType.entries.forEach { adType ->
            val recording = RecordingTelemetry()
            val events = adEvents(adType = adType, telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit -> assertFalse("${emit.name} $adType", emit.json().envelope.has("ad_format")) }
        }
    }

    // rule 8

    @Test
    fun `every event carries the session it was made with`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        assertEquals(3, telemetry.emitted.size)
        telemetry.emitted.forEach { emit ->
            assertEquals(emit.name, session.value, emit.json().envelope.getString("session_id"))
        }
    }

    // rule 9

    @Test
    fun `render_crashed names the renderer in its wire spelling, the impression and ad_load_failed carry no renderer or creative type`() {
        Renderer.entries.forEach { renderer ->
            val recording = RecordingTelemetry()
            val events = adEvents(renderer = renderer, telemetry = recording)

            events.rendered()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            events.processGone(crashed)

            assertEquals(3, recording.emitted.size)
            listOf("ad_impression", "ad_load_failed").forEach { name ->
                assertFalse("$renderer $name", recording.single(name).has("creative_type"))
                assertFalse("$renderer $name", recording.single(name).has("renderer"))
            }
            val gone = recording.single("render_crashed")
            assertEquals("$renderer", renderer.wireName, gone.getString("renderer"))
            assertFalse("$renderer", gone.has("creative_type"))
        }
        assertEquals(listOf("html", "mraid", "vast"), Renderer.entries.map { it.wireName })
    }

    @Test
    fun `the render duration runs from the first show request, not a later one`() {
        val events = adEvents()
        elapsed = 100
        events.showRequested()
        elapsed = 200
        events.showRequested()
        elapsed = 350

        events.rendered()

        assertJsonInteger(250L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `the render duration runs from the first view request, not a later one`() {
        val events = adEvents()
        elapsed = 100
        events.viewRequested()
        elapsed = 200
        events.viewRequested()
        elapsed = 350

        events.rendered()

        assertJsonInteger(250L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `the render duration runs to the first render, not a later one`() {
        val events = adEvents()
        elapsed = 100
        events.showRequested()
        elapsed = 350
        events.rendered()
        elapsed = 900

        events.rendered()

        assertEquals(
            listOf("ad_show_requested" to EventClass.DIAGNOSTIC, "ad_impression" to EventClass.COUNTED),
            telemetry.calls(),
        )
        assertJsonInteger(250L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `the render duration is read from the monotonic clock, not the wall clock`() {
        val events = adEvents()
        elapsed = 1_000
        now = 9_000_000
        events.showRequested()
        elapsed = 2_234
        now = 1_000

        events.rendered()

        assertJsonInteger(1_234L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `a render that took no measurable time is written as zero`() {
        val events = adEvents()
        elapsed = 777
        events.showRequested()

        events.rendered()

        assertJsonInteger(0L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `a render that took longer than an Int holds keeps its full length`() {
        val events = adEvents()
        elapsed = 0
        events.viewRequested()
        elapsed = 3_000_000_000L

        events.rendered()

        assertJsonInteger(3_000_000_000L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `a view request alone is enough to measure the render`() {
        val events = adEvents()
        elapsed = 100
        events.viewRequested()
        elapsed = 160

        events.rendered()

        assertJsonInteger(60L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `the show request beats the view request, whichever came first`() {
        listOf(true, false).forEach { viewFirst ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = 100
            if (viewFirst) events.viewRequested() else events.showRequested()
            elapsed = 400
            if (viewFirst) events.showRequested() else events.viewRequested()
            elapsed = 500

            events.rendered()

            val fromShow = if (viewFirst) 100L else 400L
            assertJsonInteger(fromShow, recording.single("ad_impression"), "render_duration_ms")
        }
    }

    @Test
    fun `a show request later than the render leaves the duration out instead of falling back to the view request`() {
        val events = adEvents()
        elapsed = 100
        events.viewRequested()
        elapsed = 900
        events.showRequested()
        elapsed = 500

        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    @Test
    fun `the start of the load is not a mark for the render`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()
        elapsed = 500

        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    @Test
    fun `there is no render duration without a mark`() {
        adEvents().rendered()

        assertEquals(setOf("envelope", "om_enabled"), telemetry.single("ad_impression").keyNames())
    }

    @Test
    fun `there is no render duration when the render came before the first mark`() {
        val events = adEvents()
        elapsed = 100
        events.rendered()
        elapsed = 200
        events.showRequested()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    // rule 25: calls after the first rendered() change nothing

    @Test
    fun `a mark made after the render does not change what was emitted, however often the render is called again`() {
        val events = adEvents()

        events.rendered()
        elapsed += 400
        events.showRequested()
        events.viewRequested()
        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "ad_show_requested" to EventClass.DIAGNOSTIC),
            telemetry.calls(),
        )
    }

    @Test
    fun `there is no render duration when the clock reads earlier at the render than at the mark`() {
        val events = adEvents()
        elapsed = 500
        events.showRequested()
        elapsed = 300

        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    // rule 26

    @Test
    fun `a render before the mark leaves the duration out even when both read the same instant`() {
        val events = adEvents()
        elapsed = 500
        events.rendered()
        events.showRequested()
        events.viewRequested()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    // rule 27

    @Test
    fun `marks that land between the render and the capture add no render duration`() {
        val events = adEvents()
        telemetry.beforeCapture = {
            // Once: the show request emits too, and must not run the hook again.
            telemetry.beforeCapture = {}
            elapsed = 100
            events.showRequested()
            events.viewRequested()
        }

        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    @Test
    fun `a show request that lands between the render and the capture does not replace the view request`() {
        val events = adEvents()
        elapsed = 100
        events.viewRequested()
        elapsed = 250
        telemetry.beforeCapture = {
            // Once: the show request emits too, and must not run the hook again.
            telemetry.beforeCapture = {}
            elapsed = 900
            events.showRequested()
        }

        events.rendered()

        assertJsonInteger(150L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    @Test
    fun `marks that land between the render and the capture leave the render duration as it was`() {
        val events = adEvents()
        elapsed = 100
        events.showRequested()
        elapsed = 250
        telemetry.beforeCapture = {
            // Once: the show request emits too, and must not run the hook again.
            telemetry.beforeCapture = {}
            elapsed = 900
            events.showRequested()
            events.viewRequested()
        }

        events.rendered()

        assertJsonInteger(150L, telemetry.single("ad_impression"), "render_duration_ms")
    }

    // rule 22

    @Test
    fun `there is no render duration when the difference overflows from the top of the clock to the bottom`() {
        val events = adEvents()
        elapsed = Long.MAX_VALUE
        events.showRequested()
        elapsed = Long.MIN_VALUE

        events.rendered()

        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    @Test
    fun `there is no render duration when the difference overflows from the bottom of the clock to the top`() {
        listOf(Long.MIN_VALUE to Long.MAX_VALUE, -1L to Long.MAX_VALUE, Long.MIN_VALUE to 1L).forEach { (start, end) ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = start
            events.viewRequested()
            elapsed = end

            events.rendered()

            assertFalse("$start..$end", recording.single("ad_impression").has("render_duration_ms"))
        }
    }

    @Test
    fun `the widest render duration that fits in a Long is still written`() {
        val events = adEvents()
        elapsed = 0
        events.showRequested()
        elapsed = Long.MAX_VALUE

        events.rendered()

        assertJsonInteger(Long.MAX_VALUE, telemetry.single("ad_impression"), "render_duration_ms")
    }

    // rule 10, rule 25

    @Test
    fun `only ad_impression carries the render duration and the OM flag`() {
        val events = adEvents()
        events.viewRequested()
        elapsed += 20

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        listOf("ad_load_failed", "render_crashed").forEach { name ->
            val json = telemetry.single(name)
            assertFalse(name, json.has("render_duration_ms"))
            assertFalse(name, json.has("om_enabled"))
        }
    }

    @Test
    fun `the view mark and the load marks emit nothing and touch nothing else on telemetry`() {
        val events = adEvents()

        repeat(3) {
            events.viewRequested()
            events.loadStarted()
            events.loadSucceeded()
        }

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
        assertEquals(0, telemetry.syncs)
        assertEquals(0, telemetry.captures)
    }

    // rule 11, BID-136 AC 2: the code, owner and stage of every error code

    @Test
    fun `every error code goes out as its number with its domain and its load stage`() {
        ErrorCode.entries.forEach { code ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(code)

            val json = recording.single("ad_load_failed")
            assertEquals(code.name, code.number.toString(), json.get("error_code"))
            assertEquals(code.name, code.domain.wireName, json.getString("error_domain"))
            if (code.stage != null) {
                assertEquals(code.name, code.stage.wireName, json.getString("failed_stage"))
            } else {
                assertFalse(code.name, json.has("failed_stage"))
            }
        }
    }

    @Test
    fun `the numbers and wire names the table promises for the codes raised today`() {
        val expected = mapOf(
            ErrorCode.WEBVIEW_MAIN_FRAME_ERROR to Triple("6001", "creative", "webview_load"),
            ErrorCode.MARKUP_REJECTED to Triple("6002", "device", "webview_init"),
            ErrorCode.BLANK_CREATIVE to Triple("6003", "creative", "webview_load"),
            ErrorCode.VAST_INVALID to Triple("6004", "creative", "parse"),
            ErrorCode.VAST_WRAPPER_TOO_DEEP to Triple("6005", "creative", "parse"),
            ErrorCode.VAST_WRAPPER_NO_URI to Triple("6006", "creative", "parse"),
            ErrorCode.VAST_WRAPPED_NO_AD to Triple("6014", "creative", "parse"),
            ErrorCode.VAST_WRAPPER_CLEARTEXT_BLOCKED to Triple("6015", "creative", "asset_fetch"),
            ErrorCode.NO_PLAYABLE_MEDIA to Triple("7001", "creative", null),
            ErrorCode.PLAYER_ERROR to Triple("7002", "creative", null),
            ErrorCode.VAST_WRAPPER_NO_ANSWER to Triple("8001", "network", "asset_fetch"),
            ErrorCode.VAST_WRAPPER_HTTP_STATUS to Triple("8002", "dsp", "asset_fetch"),
            ErrorCode.WEBVIEW_MAIN_FRAME_NETWORK to Triple("8003", "network", "webview_load"),
        )
        expected.forEach { (code, wire) ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(code)

            val json = recording.single("ad_load_failed")
            assertEquals(code.name, wire.first, json.get("error_code"))
            assertEquals(code.name, wire.second, json.getString("error_domain"))
            assertEquals(code.name, wire.third, json.optString("failed_stage", "").ifEmpty { null })
        }
    }

    // BID-136 AC 2: the external pair

    @Test
    fun `an external code goes out as a string with the external source of the code`() {
        ErrorCode.entries.filter { it.externalSource != null }.forEach { code ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(code, "12")

            val json = recording.single("ad_load_failed")
            assertEquals(code.name, "12", json.get("external_error_code"))
            assertEquals(code.name, code.externalSource!!.wireName, json.getString("external_error_source"))
        }
    }

    @Test
    fun `an external code goes out as the text it came as, negative and extreme ones included`() {
        listOf(Int.MIN_VALUE.toString(), "-6", "0", "404", Int.MAX_VALUE.toString(), "ERR_NAME_NOT_RESOLVED").forEach { value ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording)
                .loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR, value)

            val json = recording.single("ad_load_failed")
            assertEquals(value, json.get("external_error_code"))
            assertEquals("webview", json.getString("external_error_source"))
        }
    }

    @Test
    fun `an external code on a code that has no external source is dropped, both fields`() {
        ErrorCode.entries.filter { it.externalSource == null }.forEach { code ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(code, "12")

            val json = recording.single("ad_load_failed")
            assertFalse(code.name, json.has("external_error_code"))
            assertFalse(code.name, json.has("external_error_source"))
            assertEquals(code.name, code.number.toString(), json.get("error_code"))
        }
    }

    @Test
    fun `a code with an external source and no external error leaves both fields out`() {
        adEvents().loadFailed(ErrorCode.VAST_INVALID, externalCode = null)

        val json = telemetry.single("ad_load_failed")
        assertFalse(json.has("external_error_code"))
        assertFalse(json.has("external_error_source"))
    }

    // BID-136 AC 1: the load time

    @Test
    fun `the load time is the time from the load start to the failure`() {
        val events = adEvents()
        elapsed = 1_000
        events.loadStarted()
        elapsed = 1_420

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertJsonInteger(420L, telemetry.single("ad_load_failed"), "duration_ms")
    }

    @Test
    fun `a failure with no load start has no load time`() {
        val events = adEvents()

        events.loadFailed(ErrorCode.VAST_INVALID)

        assertFalse(telemetry.single("ad_load_failed").has("duration_ms"))
    }

    @Test
    fun `the load time counts from the first load start, and a failure at the same moment is zero`() {
        val events = adEvents()
        elapsed = 500
        events.loadStarted()
        elapsed = 900
        events.loadStarted()
        elapsed = 500

        events.loadFailed(ErrorCode.VAST_INVALID)

        assertJsonInteger(0L, telemetry.single("ad_load_failed"), "duration_ms")
    }

    @Test
    fun `a clock that went backwards or overflowed leaves the load time out`() {
        listOf(100L to 99L, Long.MAX_VALUE to Long.MIN_VALUE, Long.MIN_VALUE to Long.MAX_VALUE).forEach { (start, end) ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = start
            events.loadStarted()
            elapsed = end

            events.loadFailed(ErrorCode.VAST_INVALID)

            assertFalse("$start..$end", recording.single("ad_load_failed").has("duration_ms"))
        }
    }

    @Test
    fun `a failure at show has no load time, however long the ad waited`() {
        listOf(ErrorCode.NO_PLAYABLE_MEDIA, ErrorCode.PLAYER_ERROR).forEach { code ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = 1_000
            events.loadStarted()
            elapsed = 61_000

            events.loadFailed(code)

            assertFalse(code.name, recording.single("ad_load_failed").has("duration_ms"))
        }
    }

    // BID-136 AC 3: errors are never sampled out

    @Test
    fun `the gate keeps a load failure at rate 1 for a day when sampling and the per-name rate are zero`() {
        val gate = TelemetryGate(
            session,
            RateLimiter(monotonic),
            TelemetryConfig(samplingRate = 0.0, eventRates = mapOf("ad_load_failed" to 0.0)),
        )
        val captured = mutableListOf<Pair<EventClass, TelemetryEvent>>()
        val gated = object : Telemetry {
            override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
                gate.admit(eventName, eventClass)?.let { captured += eventClass to capture(it) }
            }

            override fun sync() = Unit
        }

        adEvents(telemetry = gated).loadFailed(ErrorCode.VAST_INVALID, "100")

        val (eventClass, event) = captured.single()
        assertEquals(EventClass.ERROR, eventClass)
        assertEquals(EventClass.ERROR, event.eventClass)
        assertEquals(TimeUnit.HOURS.toMillis(24), eventClass.ttlMillis)
        val json = JSONObject(event.encode().toString(Charsets.UTF_8))
        assertEquals("ad_load_failed", json.envelope.getString("event_name"))
        assertEquals(1.0, json.envelope.getDouble("sampling_rate"), 0.0)
        assertEquals("6004", json.get("error_code"))
    }

    // BID-136 AC 5 and AC 6: exception class names

    @Test
    fun `the exception class is the class name of the throwable, and a failure without one leaves it out`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = IllegalArgumentException("never sent"))
        val without = RecordingTelemetry()
        adEvents(telemetry = without).loadFailed(ErrorCode.MARKUP_REJECTED)

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalArgumentException", json.getString("exception_class"))
        assertFalse(json.has("exception_cause_class"))
        assertFalse(without.single("ad_load_failed").has("exception_class"))
        assertFalse(without.single("ad_load_failed").has("exception_cause_class"))
    }

    @Test
    fun `the exception message is never sent`() {
        val marker = "https://creative.invalid/secret?token=abc123"
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = IllegalStateException(marker, IOException(marker)))

        val text = telemetry.emitted.single().event!!.encode().toString(Charsets.UTF_8)
        assertFalse(text, "creative.invalid" in text)
        assertFalse(text, "abc123" in text)
        assertTrue(text, "java.lang.IllegalStateException" in text)
    }

    @Test
    fun `the cause class is the root cause, not the first cause`() {
        val chain = IllegalStateException("a", IllegalArgumentException("b", IOException("c", ConnectException("d"))))

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = chain)

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals("java.net.ConnectException", json.getString("exception_cause_class"))
    }

    @Test
    fun `a throwable whose root is its own class has no cause class`() {
        val chain = IllegalStateException("a", IllegalArgumentException("b", IllegalStateException("c")))

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = chain)

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertFalse(json.has("exception_cause_class"))
    }

    @Test
    fun `a throwable with no cause has no cause class`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = IOException("x"))

        assertFalse(telemetry.single("ad_load_failed").has("exception_cause_class"))
    }

    @Test
    fun `a cause that points back at the throwable ends the walk`() {
        val first = LoopingException()
        val second = LoopingException()
        first.next = second
        second.next = first

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = first)

        val json = telemetry.single("ad_load_failed")
        assertEquals(LoopingException::class.java.name, json.getString("exception_class"))
        assertFalse(json.has("exception_cause_class"))
    }

    @Test
    fun `a cycle between two classes ends the walk at the last throwable before it repeats`() {
        val outer = IllegalStateException("outer")
        val loop = LoopingException()
        val back = BackReferringException(outer)
        loop.next = back
        outer.initCause(loop)

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = outer)

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals(BackReferringException::class.java.name, json.getString("exception_cause_class"))
    }

    @Test
    fun `a cycle of two throwables reports the second as the root`() {
        val outer = IllegalStateException("outer")
        val loop = LoopingException()
        loop.next = outer
        outer.initCause(loop)

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = outer)

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals(LoopingException::class.java.name, json.getString("exception_cause_class"))
    }

    @Test
    fun `a throwable that is its own cause ends the walk`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = SelfCause())

        val json = telemetry.single("ad_load_failed")
        assertEquals(SelfCause::class.java.name, json.getString("exception_class"))
        assertFalse(json.has("exception_cause_class"))
    }

    @Test
    fun `a cause whose getCause throws ends the walk at the throwable before it`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = IllegalStateException("a", ThrowingCause()))

        val json = telemetry.single("ad_load_failed")
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals(ThrowingCause::class.java.name, json.getString("exception_cause_class"))
    }

    @Test
    fun `a throwable whose own getCause throws still reports its class`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = ThrowingCause())

        val json = telemetry.single("ad_load_failed")
        assertEquals(ThrowingCause::class.java.name, json.getString("exception_class"))
        assertFalse(json.has("exception_cause_class"))
    }

    @Test
    fun `a chain of ten thousand causes is reported once, its root taken 32 causes down`() {
        var chain: Throwable? = null
        for (depth in 10_000 downTo 0) {
            chain = when (depth) {
                32 -> IOException("link", chain)
                33 -> ConnectException("link").apply { initCause(chain) }
                else -> IllegalStateException("link", chain)
            }
        }

        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = chain)

        val json = telemetry.single("ad_load_failed")
        assertEquals("6002", json.get("error_code"))
        assertEquals("java.lang.IllegalStateException", json.getString("exception_class"))
        assertEquals("java.io.IOException", json.getString("exception_cause_class"))
    }

    @Test
    fun `a nested class name is sent with its dollar sign`() {
        adEvents().loadFailed(ErrorCode.MARKUP_REJECTED, cause = SelfCause())

        assertEquals(
            "org.bidon.rendering.telemetry.AdEventsTest\$SelfCause",
            telemetry.single("ad_load_failed").getString("exception_class"),
        )
    }

    // rule 12

    @Test
    fun `the crash flag goes out as a JSON boolean, as passed`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).processGone(ProcessExit(didCrash, priorityAtExit = null))

            assertEquals("did_crash for $didCrash", didCrash, recording.single("render_crashed").get("did_crash"))
        }
    }

    @Test
    fun `an unknown cause leaves did_crash out and the event is still sent`() {
        adEvents().processGone(ProcessExit(didCrash = null, priorityAtExit = RendererPriority.IMPORTANT))

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_crashed")
        assertFalse(json.has("did_crash"))
        assertEquals("important", json.get("renderer_priority_at_exit"))
        assertEquals(false, json.get("was_shown"))
    }

    @Test
    fun `an unknown priority leaves renderer_priority_at_exit out and the event is still sent`() {
        adEvents().processGone(ProcessExit(didCrash = false, priorityAtExit = null))

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_crashed")
        assertFalse(json.has("renderer_priority_at_exit"))
        assertEquals(false, json.get("did_crash"))
    }

    @Test
    fun `no detail at all leaves both did_crash and renderer_priority_at_exit out`() {
        adEvents().processGone(ProcessExit(didCrash = null, priorityAtExit = null))

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_crashed")
        assertFalse(json.has("did_crash"))
        assertFalse(json.has("renderer_priority_at_exit"))
        assertEquals(setOf("envelope", "renderer", "was_shown"), json.keyNames())
    }

    @Test
    fun `each priority goes out under its lower case wire name, as a string`() {
        val expected = mapOf(
            RendererPriority.WAIVED to "waived",
            RendererPriority.BOUND to "bound",
            RendererPriority.IMPORTANT to "important",
        )
        assertEquals(expected.keys, RendererPriority.entries.toSet())
        expected.forEach { (priority, wire) ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).processGone(ProcessExit(didCrash = false, priorityAtExit = priority))

            assertEquals("$priority", wire, recording.single("render_crashed").get("renderer_priority_at_exit"))
            assertEquals("$priority", wire, priority.wireName)
        }
    }

    @Test
    fun `a process death before any impression says was_shown false`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            events.loadStarted()
            events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

            events.processGone(ProcessExit(didCrash, priorityAtExit = null))

            assertEquals("was_shown for $didCrash", false, recording.single("render_crashed").get("was_shown"))
        }
    }

    @Test
    fun `a process death after an impression says was_shown true`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            events.rendered()

            events.processGone(ProcessExit(didCrash, priorityAtExit = null))

            val json = recording.single("render_crashed")
            assertEquals("was_shown for $didCrash", true, json.get("was_shown"))
            assertEquals("did_crash for $didCrash", didCrash, json.get("did_crash"))
        }
    }

    @Test
    fun `an impression that telemetry did not keep still makes was_shown true`() {
        val events = adEvents()
        telemetry.rate = null
        events.rendered()
        telemetry.rate = SamplingRate.ALL

        events.processGone(crashed)

        assertEquals(true, telemetry.single("render_crashed").get("was_shown"))
    }

    @Test
    fun `an impression after the process death was reported does not make was_shown true`() {
        val events = adEvents()

        events.processGone(killed)
        events.rendered()

        assertEquals(false, telemetry.single("render_crashed").get("was_shown"))
    }

    // rule 30

    @Test
    fun `an impression that lands while the process death is being emitted does not make was_shown true`() {
        val events = adEvents()
        telemetry.beforeCapture = { events.rendered() }

        events.processGone(killed)

        assertEquals(false, telemetry.single("render_crashed").get("was_shown"))
        assertEquals(
            setOf("render_crashed" to EventClass.ERROR, "ad_impression" to EventClass.COUNTED),
            telemetry.calls().toSet(),
        )
    }

    // rule 13

    @Test
    fun `only the first render emits`() {
        val events = adEvents()

        repeat(5) { events.rendered() }

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
    }

    // rule 13, rule 25: calls after the first rendered() change nothing

    @Test
    fun `a later render after a mark does not emit either`() {
        val events = adEvents()
        events.rendered()
        events.showRequested()
        elapsed += 10

        events.rendered()

        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "ad_show_requested" to EventClass.DIAGNOSTIC),
            telemetry.calls(),
        )
        assertFalse(telemetry.single("ad_impression").has("render_duration_ms"))
    }

    // rule 14

    @Test
    fun `only the first load failure emits, and it keeps its own code and external error`() {
        val events = adEvents()

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.loadFailed(ErrorCode.PLAYER_ERROR, "7")
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.loadFailed(ErrorCode.VAST_INVALID, "100", IOException())

        assertEquals(listOf("ad_load_failed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("ad_load_failed")
        assertEquals("6001", json.get("error_code"))
        assertFalse(json.has("external_error_code"))
        assertFalse(json.has("exception_class"))
    }

    @Test
    fun `a first failure with an external error is not replaced by a later one without`() {
        val events = adEvents()

        events.loadFailed(ErrorCode.NO_PLAYABLE_MEDIA, "-3")
        events.loadFailed(ErrorCode.MARKUP_REJECTED)

        val json = telemetry.single("ad_load_failed")
        assertEquals("7001", json.get("error_code"))
        assertEquals("-3", json.get("external_error_code"))
        assertEquals("vast", json.getString("external_error_source"))
    }

    // rule 15

    @Test
    fun `only the first process death emits, and it keeps its own crash flag`() {
        val events = adEvents()

        events.processGone(crashed)
        events.processGone(killed)
        events.processGone(crashed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_crashed")
        assertEquals(true, json.get("did_crash"))
        assertEquals(false, json.get("was_shown"))
    }

    @Test
    fun `several deaths for one ad give one event that keeps the first death's crash flag and priority`() {
        val events = adEvents()

        events.processGone(ProcessExit(didCrash = true, priorityAtExit = RendererPriority.IMPORTANT))
        events.processGone(ProcessExit(didCrash = false, priorityAtExit = RendererPriority.WAIVED))
        events.processGone(ProcessExit(didCrash = null, priorityAtExit = null))

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_crashed")
        assertEquals(true, json.get("did_crash"))
        assertEquals("important", json.get("renderer_priority_at_exit"))
    }

    @Test
    fun `a first death with no detail is not filled in by a later one`() {
        val events = adEvents()

        events.processGone(ProcessExit(didCrash = null, priorityAtExit = null))
        events.processGone(ProcessExit(didCrash = true, priorityAtExit = RendererPriority.BOUND))

        val json = telemetry.single("render_crashed")
        assertFalse(json.has("did_crash"))
        assertFalse(json.has("renderer_priority_at_exit"))
    }

    // rule 16

    @Test
    fun `a load failure after the render process died does not emit`() {
        val events = adEvents()

        events.processGone(crashed)
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a load failure before the render process died does not stop the death being reported`() {
        val events = adEvents()

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(killed)

        assertEquals(
            listOf("ad_load_failed" to EventClass.ERROR, "render_crashed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    // rule 16, rule 18, rule 25

    @Test
    fun `a process death that was not kept still stops a later load failure`() {
        val events = adEvents()
        telemetry.rate = null
        events.processGone(crashed)
        telemetry.rate = SamplingRate.ALL

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
    }

    // rule 17

    @Test
    fun `a failure after the render is still reported`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(ErrorCode.PLAYER_ERROR)

        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "ad_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `a render after a failure is still reported`() {
        val events = adEvents()

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.rendered()

        assertEquals(
            listOf("ad_load_failed" to EventClass.ERROR, "ad_impression" to EventClass.COUNTED),
            telemetry.calls(),
        )
    }

    // rule 23

    @Test
    fun `a render after the render process died is still reported`() {
        val events = adEvents()

        events.processGone(crashed)
        events.rendered()

        assertEquals(
            listOf("render_crashed" to EventClass.ERROR, "ad_impression" to EventClass.COUNTED),
            telemetry.calls(),
        )
    }

    @Test
    fun `a render before the render process died does not stop the death being reported`() {
        val events = adEvents()

        events.rendered()
        events.processGone(crashed)

        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "render_crashed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    // rule 18

    @Test
    fun `a render that telemetry did not keep still uses up the render`() {
        val events = adEvents()
        telemetry.rate = null
        events.rendered()
        telemetry.rate = SamplingRate.ALL

        events.rendered()

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `a load failure that telemetry did not keep still uses up the load failure`() {
        val events = adEvents()
        telemetry.rate = null
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        telemetry.rate = SamplingRate.ALL

        events.loadFailed(ErrorCode.PLAYER_ERROR)

        assertEquals(listOf("ad_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `a process death that telemetry did not keep still uses up the process death`() {
        val events = adEvents()
        telemetry.rate = null
        events.processGone(crashed)
        telemetry.rate = SamplingRate.ALL

        events.processGone(killed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `when telemetry keeps nothing, a second round of every call emits nothing`() {
        val events = adEvents()
        telemetry.rate = null
        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)
        telemetry.rate = SamplingRate.ALL

        events.rendered()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(crashed)

        assertEquals(
            listOf(
                "ad_impression" to EventClass.COUNTED,
                "ad_load_failed" to EventClass.ERROR,
                "render_crashed" to EventClass.ERROR,
            ),
            telemetry.calls(),
        )
        assertEquals(0, telemetry.captures)
    }

    // rule 19

    @Test
    fun `renders racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, _ -> events.rendered() }
            .forEach { calls -> assertEquals(listOf("ad_impression" to EventClass.COUNTED), calls) }
    }

    @Test
    fun `load failures racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, index -> events.loadFailed(ErrorCode.entries[index % ErrorCode.entries.size]) }
            .forEach { calls -> assertEquals(listOf("ad_load_failed" to EventClass.ERROR), calls) }
    }

    @Test
    fun `process deaths racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, index -> events.processGone(if (index % 2 == 0) crashed else killed) }
            .forEach { calls -> assertEquals(listOf("render_crashed" to EventClass.ERROR), calls) }
    }

    @Test
    fun `a process death racing load failures emits once, with at most one load failure`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            if (index % 2 == 0) events.processGone(crashed)
            else events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        }

        outcomes.forEach { calls ->
            assertEquals(calls.toString(), 1, calls.count { it.first == "render_crashed" })
            assertTrue(calls.toString(), calls.count { it.first == "ad_load_failed" } <= 1)
            assertEquals(calls.toString(), calls.size, calls.count { it.first in RACED_NAMES })
        }
    }

    @Test
    fun `every kind of call racing on many threads emits each event at most once`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            when (index % 6) {
                0 -> events.rendered()
                1 -> events.loadFailed(ErrorCode.PLAYER_ERROR)
                2 -> events.processGone(killed)
                3 -> events.loadStarted()
                4 -> events.showRequested()
                else -> events.viewRequested()
            }
        }

        outcomes.forEach { calls ->
            assertEquals(calls.toString(), 1, calls.count { it.first == "ad_impression" })
            assertEquals(calls.toString(), 1, calls.count { it.first == "render_crashed" })
            assertTrue(calls.toString(), calls.count { it.first == "ad_load_failed" } <= 1)
        }
    }

    // rule 19, rule 21, rule 24: a second call made while the first is still inside emit, from another thread.

    @Test
    fun `a render made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.rendered() } }

        events.rendered()

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
    }

    @Test
    fun `a load failure made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.loadFailed(ErrorCode.PLAYER_ERROR) } }

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(listOf("ad_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals("6001", telemetry.single("ad_load_failed").get("error_code"))
    }

    @Test
    fun `a process death made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.processGone(killed) } }

        events.processGone(crashed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a load failure made while a process death is being emitted lost the race, so it does not emit`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR) } }

        events.processGone(crashed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a process death made while a load failure is being emitted still emits, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.processGone(crashed) } }

        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(
            setOf("ad_load_failed" to EventClass.ERROR, "render_crashed" to EventClass.ERROR),
            telemetry.calls().toSet(),
        )
        assertEquals(2, telemetry.emitted.size)
    }

    @Test
    fun `a render made while a failure is being emitted still emits, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.rendered() } }

        events.loadFailed(ErrorCode.PLAYER_ERROR)

        assertEquals(
            setOf("ad_load_failed" to EventClass.ERROR, "ad_impression" to EventClass.COUNTED),
            telemetry.calls().toSet(),
        )
    }

    @Test
    fun `a load start or a mark made while an event is being emitted does not wait for it`() {
        val events = adEvents()
        telemetry.whileEmitting = {
            onAnotherThread {
                events.loadStarted()
                events.showRequested()
                events.viewRequested()
            }
        }

        events.rendered()

        assertEquals(
            listOf("ad_show_requested" to EventClass.DIAGNOSTIC, "ad_impression" to EventClass.COUNTED),
            telemetry.calls(),
        )
    }

    // rule 28

    @Test
    fun `closing the ad emits nothing and touches nothing else on telemetry`() {
        val events = adEvents()

        repeat(3) { events.closed() }

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `closing after every event emits nothing more`() {
        val events = adEvents()
        events.rendered()
        events.loadFailed(ErrorCode.PLAYER_ERROR)

        events.closed()
        events.closed()

        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "ad_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `a process death after the ad was closed emits nothing`() {
        val events = adEvents()
        events.rendered()
        events.closed()

        events.processGone(crashed)
        events.processGone(killed)

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
    }

    @Test
    fun `a process death after the ad was closed still stops a later load failure`() {
        val events = adEvents()
        events.closed()

        events.processGone(crashed)
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
    }

    @Test
    fun `a process death before the ad was closed is still reported, once`() {
        val events = adEvents()

        events.processGone(crashed)
        events.closed()
        events.processGone(killed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
        assertEquals(true, telemetry.single("render_crashed").get("did_crash"))
    }

    @Test
    fun `closing the ad does not stop a render or a load failure`() {
        val events = adEvents()
        events.closed()

        events.rendered()
        events.loadFailed(ErrorCode.VAST_INVALID)

        assertEquals(
            listOf("ad_impression" to EventClass.COUNTED, "ad_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `closing the ad does not use up the render or the load failure`() {
        val events = adEvents()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.closed()
        events.rendered()
        events.closed()

        events.rendered()
        events.loadFailed(ErrorCode.PLAYER_ERROR)

        assertEquals(
            listOf("ad_load_failed" to EventClass.ERROR, "ad_impression" to EventClass.COUNTED),
            telemetry.calls(),
        )
    }

    // rule 29

    @Test
    fun `a process death being emitted when the ad is closed on another thread is still emitted once, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.closed() } }

        events.processGone(crashed)

        assertEquals(listOf("render_crashed" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `closing the ad while a render is being emitted does not wait for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.closed() } }

        events.rendered()

        assertEquals(listOf("ad_impression" to EventClass.COUNTED), telemetry.calls())
    }

    @Test
    fun `process deaths racing a close emit at most one death, and no other event`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            if (index % 2 == 0) events.closed() else events.processGone(crashed)
        }

        outcomes.forEach { calls ->
            assertTrue(calls.toString(), calls.size <= 1)
            assertTrue(calls.toString(), calls.all { it == ("render_crashed" to EventClass.ERROR) })
        }
    }

    @Test
    fun `a close racing process deaths and load failures emits at most one of each, and no other event`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            when (index % 4) {
                0 -> events.closed()
                1, 2 -> events.processGone(killed)
                else -> events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
            }
        }

        outcomes.forEach { calls ->
            assertTrue(calls.toString(), calls.count { it.first == "render_crashed" } <= 1)
            assertTrue(calls.toString(), calls.count { it.first == "ad_load_failed" } <= 1)
            assertTrue(calls.toString(), calls.all { it.first in RACED_NAMES })
        }
    }

    // rule 20

    @Test
    fun `no call throws with telemetry switched off, in any order`() {
        val calls: List<(AdEvents) -> Unit> = listOf(
            { it.loadStarted() },
            { it.showRequested() },
            { it.viewRequested() },
            { it.rendered() },
            { it.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR, Int.MIN_VALUE.toString()) },
            { it.processGone(crashed) },
            { it.closed() },
            { it.loadSucceeded() },
        )
        permutations(calls).forEach { order ->
            val events = adEvents(telemetry = Telemetry.Disabled)
            repeat(2) { order.forEach { call -> call(events) } }
        }
    }

    // rule 20, rule 22

    @Test
    fun `capturing and encoding never throw for extreme clock readings, and an overflowing duration is left out`() {
        val readings = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        readings.forEach { wall ->
            readings.forEach { start ->
                readings.forEach { end ->
                    val recording = RecordingTelemetry()
                    now = wall
                    val events = adEvents(telemetry = recording)
                    elapsed = start
                    events.loadStarted()
                    events.viewRequested()
                    elapsed = end

                    events.rendered()
                    events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR, Int.MAX_VALUE.toString())
                    events.processGone(crashed)

                    assertEquals(3, recording.emitted.size)
                    recording.emitted.forEach { emit -> assertEquals(wall, emit.json().envelope.getLong("event_ts")) }
                    val expected = runCatching { Math.subtractExact(end, start) }.getOrNull()?.takeIf { it >= 0 }
                    val rendered = recording.single("ad_impression")
                    if (expected != null) {
                        assertJsonInteger(expected, rendered, "render_duration_ms")
                    } else {
                        assertFalse("$start..$end", rendered.has("render_duration_ms"))
                    }
                    val failed = recording.single("ad_load_failed")
                    if (expected != null) {
                        assertJsonInteger(expected, failed, "duration_ms")
                    } else {
                        assertFalse("$start..$end", failed.has("duration_ms"))
                    }
                }
            }
        }
    }

    @Test
    fun `capturing never throws for any constructor value`() {
        val auctionIds = listOf(null, "", " ", "\u0000", "\"\\", "\uD800", "𓀀".repeat(2_000))
        val adTypes = listOf<AdType?>(null) + AdType.entries
        auctionIds.forEach { auctionId ->
            adTypes.forEach { adType ->
                Renderer.entries.forEach { renderer ->
                    val recording = RecordingTelemetry()
                    val events = adEvents(auctionId, adType, renderer, telemetry = recording)

                    events.rendered()
                    events.loadFailed(ErrorCode.MARKUP_REJECTED)
                    events.processGone(killed)

                    assertEquals(3, recording.emitted.size)
                    recording.emitted.forEach { assertNotNull(it.event!!.encode()) }
                }
            }
        }
    }

    // BID-140 AC 1: ad_show_requested and the time since the load ended

    @Test
    fun `a show request emits one ad_show_requested as a diagnostic event`() {
        val events = adEvents()
        events.loadSucceeded()

        events.showRequested()

        assertEquals(listOf("ad_show_requested" to EventClass.DIAGNOSTIC), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `ad_show_requested is the envelope plus the time since the load ended, and nothing else`() {
        telemetry.rate = SamplingRate.of(0.25)!!
        val events = adEvents(renderer = Renderer.VAST)
        events.loadSucceeded()
        elapsed += 2_500

        events.showRequested()

        val json = telemetry.single("ad_show_requested")
        assertEquals(setOf("envelope", "time_since_loaded_ms"), json.keyNames())
        assertEnvelope(json.envelope, "ad_show_requested", rate = 0.25)
        assertJsonInteger(2_500L, json, "time_since_loaded_ms")
    }

    @Test
    fun `the time since the load ended is read from the monotonic clock, not the wall clock`() {
        val events = adEvents()
        events.loadSucceeded()
        elapsed += 2_500
        now -= 3_600_000

        events.showRequested()

        val json = telemetry.single("ad_show_requested")
        assertJsonInteger(2_500L, json, "time_since_loaded_ms")
        assertEquals(now, json.envelope.getLong("event_ts"))
    }

    @Test
    fun `a wall clock moved forward in between does not change the time since the load ended`() {
        val events = adEvents()
        events.loadSucceeded()
        elapsed += 700
        now += 86_400_000

        events.showRequested()

        assertJsonInteger(700L, telemetry.single("ad_show_requested"), "time_since_loaded_ms")
    }

    @Test
    fun `a show request with no load leaves the time since the load ended out`() {
        adEvents().showRequested()

        assertEquals(setOf("envelope"), telemetry.single("ad_show_requested").keyNames())
    }

    @Test
    fun `the start of the load is not the end of it`() {
        val events = adEvents()
        events.loadStarted()
        elapsed += 900

        events.showRequested()

        assertFalse(telemetry.single("ad_show_requested").has("time_since_loaded_ms"))
    }

    @Test
    fun `the time since the load ended counts from the first loadSucceeded, not a later one`() {
        val events = adEvents()
        events.loadSucceeded()
        elapsed += 100
        events.loadSucceeded()
        elapsed += 400

        events.showRequested()

        assertJsonInteger(500L, telemetry.single("ad_show_requested"), "time_since_loaded_ms")
    }

    @Test
    fun `each show request measures from the load to its own call`() {
        val events = adEvents()
        events.loadSucceeded()
        elapsed += 2_500
        events.showRequested()
        elapsed += 1_500

        events.showRequested()

        val times = telemetry.emitted.filter { it.name == "ad_show_requested" }.map { it.json().getLong("time_since_loaded_ms") }
        assertEquals(listOf(2_500L, 4_000L), times)
    }

    @Test
    fun `a show request at the very moment the load ended is zero`() {
        val events = adEvents()
        events.loadSucceeded()

        events.showRequested()

        assertJsonInteger(0L, telemetry.single("ad_show_requested"), "time_since_loaded_ms")
    }

    @Test
    fun `a show request before the load ended has no time, and a later one measures from the end of the load`() {
        val events = adEvents()
        elapsed = 5_000
        events.showRequested()
        elapsed = 6_000
        events.loadSucceeded()
        elapsed = 9_000

        events.showRequested()

        val times = telemetry.emitted.filter { it.name == "ad_show_requested" }
        assertFalse(times[0].json().has("time_since_loaded_ms"))
        assertJsonInteger(3_000L, times[1].json(), "time_since_loaded_ms")
    }

    @Test
    fun `the time since the load ended is left out when the clock reads earlier at the show or overflows, and written when widest`() {
        listOf(
            500L to 300L,
            Long.MAX_VALUE to Long.MIN_VALUE,
            Long.MIN_VALUE to Long.MAX_VALUE,
            -1L to Long.MAX_VALUE,
        ).forEach { (loaded, shown) ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = loaded
            events.loadSucceeded()
            elapsed = shown

            events.showRequested()

            assertFalse("$loaded..$shown", recording.single("ad_show_requested").has("time_since_loaded_ms"))
        }
        val recording = RecordingTelemetry()
        val events = adEvents(telemetry = recording)
        elapsed = 0
        events.loadSucceeded()
        elapsed = Long.MAX_VALUE

        events.showRequested()

        assertJsonInteger(Long.MAX_VALUE, recording.single("ad_show_requested"), "time_since_loaded_ms")
    }

    // BID-140 AC 2: one ad_show_requested per call

    @Test
    fun `a show request after a failed load, a death or a close still emits ad_show_requested`() {
        val events = adEvents()
        events.loadFailed(ErrorCode.WEBVIEW_MAIN_FRAME_ERROR)
        events.processGone(killed)
        events.closed()

        events.showRequested()

        assertEquals(
            listOf(
                "ad_load_failed" to EventClass.ERROR,
                "render_crashed" to EventClass.ERROR,
                "ad_show_requested" to EventClass.DIAGNOSTIC,
            ),
            telemetry.calls(),
        )
    }

    @Test
    fun `a view request emits no ad_show_requested`() {
        val events = adEvents()
        events.loadSucceeded()

        repeat(3) { events.viewRequested() }

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
    }

    // BID-140: threads

    @Test
    fun `show requests racing on many threads each emit`() {
        val outcomes = race(rounds = ROUNDS) { events, _ -> events.showRequested() }

        outcomes.forEach { calls ->
            assertEquals(calls.toString(), THREADS, calls.count { it.first == "ad_show_requested" })
        }
    }

    // Helpers

    /**
     * Runs [THREADS] calls of [call] at once on a fresh [AdEvents] that [setup] has prepared, [rounds] times, and
     * returns what each round emitted. The threads are released together by a latch once all of them are waiting on it.
     */
    private fun race(
        rounds: Int,
        setup: (AdEvents) -> Unit = {},
        call: (AdEvents, Int) -> Unit,
    ): List<List<Pair<String, EventClass>>> {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            return List(rounds) {
                val recording = RecordingTelemetry()
                val events = adEvents(telemetry = recording)
                elapsed = 0
                setup(events)
                val ready = CountDownLatch(THREADS)
                val go = CountDownLatch(1)
                val futures = List(THREADS) { index ->
                    pool.submit {
                        ready.countDown()
                        go.await()
                        call(events, index)
                    }
                }
                assertTrue("threads ready", ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                futures.forEach { it.get(5, TimeUnit.SECONDS) }
                recording.calls()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Runs [block] on a new thread and records a failure if it throws or has not returned within a second. Failures are
     * checked after the test rather than thrown here, inside emit, where [AdEvents] might swallow them.
     */
    private fun onAnotherThread(block: () -> Unit) {
        val other = thread { runCatching(block).onFailure { otherThreadFailures += it } }
        other.join(1_000)
        if (other.isAlive) otherThreadFailures += AssertionError("the call on another thread is still waiting")
    }

    @After
    fun `nothing failed on another thread`() {
        assertEquals(emptyList<Throwable>(), otherThreadFailures.toList())
    }

    private class LoopingException : RuntimeException() {
        var next: Throwable? = null
        override val cause: Throwable? get() = next
    }

    private class BackReferringException(private val target: Throwable) : RuntimeException() {
        override val cause: Throwable? get() = target
    }

    private class SelfCause : RuntimeException() {
        override val cause: Throwable? get() = this
    }

    private class ThrowingCause : RuntimeException() {
        override val cause: Throwable? get() = throw IllegalStateException("getCause is not allowed")
    }

    private val JSONObject.envelope get() = getJSONObject("envelope")

    private fun assertEnvelope(json: JSONObject, name: String, rate: Double) {
        assertEquals(ENVELOPE_KEYS, json.keyNames())
        assertEquals(4, UUID.fromString(json.getString("event_id")).version())
        assertEquals(name, json.getString("event_name"))
        assertEquals(now, json.getLong("event_ts"))
        assertEquals(AUCTION, json.getString("auction_id"))
        assertEquals(session.value, json.getString("session_id"))
        assertEquals("interstitial", json.getString("ad_type"))
        assertEquals(rate, json.getDouble("sampling_rate"), 0.0)
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    private companion object {
        const val AUCTION = "b622d42a-cab5-498f-9582-39e7f9da09e9"
        const val THREADS = 8
        const val ROUNDS = 500

        val ENVELOPE_KEYS = setOf(
            "event_id", "event_name", "event_ts", "auction_id", "session_id", "ad_type", "sampling_rate",
        )

        val RACED_NAMES = setOf("render_crashed", "ad_load_failed")
    }
}

/** Rule 25. Fails unless [key] holds a JSON integer (not a string, not a fraction) equal to [expected]. */
internal fun assertJsonInteger(expected: Long, json: JSONObject, key: String) {
    val value = json.get(key)
    assertTrue("$key is $value (${value::class.java.simpleName})", value is Int || value is Long)
    assertEquals(key, expected, (value as Number).toLong())
}

/**
 * A telemetry that records every call. It calls capture at [rate], or never when [rate] is `null` (sampled out,
 * rate-limited or switched off), and runs [beforeCapture] and then, once, [whileEmitting] inside emit, before capture.
 */
internal class RecordingTelemetry : Telemetry {

    class Emitted(val name: String, val eventClass: EventClass, val event: TelemetryEvent?) {
        fun json(): JSONObject = JSONObject(event!!.encode().toString(Charsets.UTF_8))
    }

    @Volatile
    var rate: SamplingRate? = SamplingRate.ALL

    @Volatile
    var beforeCapture: () -> Unit = {}

    @Volatile
    var whileEmitting: (() -> Unit)? = null

    val emitted = CopyOnWriteArrayList<Emitted>()

    @Volatile
    var syncs = 0
        private set

    val captures: Int get() = emitted.count { it.event != null }

    fun calls(): List<Pair<String, EventClass>> = emitted.map { it.name to it.eventClass }

    /** The one event emitted under [name], decoded. Fails if there is not exactly one. */
    fun single(name: String): JSONObject {
        val matching = emitted.filter { it.name == name }
        assertEquals("events named $name in ${calls()}", 1, matching.size)
        return matching.single().json()
    }

    override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
        whileEmitting?.let { hook ->
            whileEmitting = null
            hook()
        }
        val kept = rate ?: run {
            emitted += Emitted(eventName, eventClass, null)
            return
        }
        beforeCapture()
        emitted += Emitted(eventName, eventClass, capture(kept))
    }

    override fun sync() {
        syncs++
    }
}
