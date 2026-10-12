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
        creative: Creative = Creative.HTML,
        telemetry: Telemetry = this.telemetry,
    ) = AdEvents(telemetry, session, auctionId, adType, creative, clock, monotonic)

    // rule 1

    @Test
    fun `rendered emits one creative_rendered as a diagnostic`() {
        adEvents().rendered()

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `a load failure emits one creative_load_failed as an error`() {
        adEvents().loadFailed(LoadFailure.WEBVIEW_ERROR, -2)

        assertEquals(listOf("creative_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    @Test
    fun `a gone render process emits one render_process_gone as an error`() {
        adEvents().processGone(didCrash = true)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.syncs)
    }

    // rule 1, rule 21

    @Test
    fun `starting and finishing a load emit nothing and touch nothing else on telemetry`() {
        val events = adEvents()

        repeat(3) {
            events.loadStarted()
            events.loaded()
        }

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
        assertEquals(0, telemetry.syncs)
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `the three events emit once each, in the order they were called`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(LoadFailure.VAST_ERROR)
        events.processGone(didCrash = false)

        assertEquals(3, telemetry.captures)
        assertEquals(
            listOf(
                "creative_rendered" to EventClass.DIAGNOSTIC,
                "creative_load_failed" to EventClass.ERROR,
                "render_process_gone" to EventClass.ERROR,
            ),
            telemetry.calls(),
        )
    }

    // rule 2

    @Test
    fun `every captured event carries the class it was emitted with`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(LoadFailure.PLAYBACK_ERROR, 4)
        events.processGone(didCrash = true)

        telemetry.emitted.forEach { emit ->
            assertEquals(emit.name, emit.eventClass, emit.event!!.eventClass)
        }
        assertEquals(
            listOf(EventClass.DIAGNOSTIC, EventClass.ERROR, EventClass.ERROR),
            telemetry.emitted.map { it.event!!.eventClass },
        )
    }

    // rule 3, rule 8, rule 9, rule 10

    @Test
    fun `creative_rendered is the envelope plus the creative type and the load time, and nothing else`() {
        telemetry.rate = SamplingRate.of(0.25)!!
        val events = adEvents(creative = Creative.MRAID)
        elapsed = 1_000
        events.loadStarted()
        elapsed = 1_250
        events.loaded()

        events.rendered()

        val json = telemetry.single("creative_rendered")
        assertEquals(setOf("envelope", "creative_type", "load_ms"), json.keySet())
        assertEnvelope(json.envelope, "creative_rendered", rate = 0.25)
        assertEquals("mraid", json.getString("creative_type"))
        assertJsonInteger(250L, json, "load_ms")
    }

    @Test
    fun `an event's own fields sit next to the envelope, not inside it`() {
        val events = adEvents(creative = Creative.MRAID)

        events.rendered()

        val json = telemetry.single("creative_rendered")
        assertEquals("mraid", json.getString("creative_type"))
        assertFalse(json.envelope.has("creative_type"))
    }

    @Test
    fun `creative_load_failed is the envelope plus the creative type, the reason and the code, and nothing else`() {
        telemetry.rate = SamplingRate.of(0.5)!!
        val events = adEvents(creative = Creative.VAST)
        events.loadStarted()
        elapsed += 30
        events.loaded()

        events.loadFailed(LoadFailure.NO_MEDIA_FILE, 403)

        val json = telemetry.single("creative_load_failed")
        assertEquals(setOf("envelope", "creative_type", "reason", "code"), json.keySet())
        assertEnvelope(json.envelope, "creative_load_failed", rate = 0.5)
        assertEquals("vast", json.getString("creative_type"))
        assertEquals("no_media_file", json.getString("reason"))
        assertJsonInteger(403L, json, "code")
    }

    @Test
    fun `render_process_gone is the envelope plus the creative type and the two flags, and nothing else`() {
        telemetry.rate = SamplingRate.of(0.125)!!
        val events = adEvents(creative = Creative.HTML)
        events.loadStarted()
        elapsed += 30
        events.loaded()

        events.processGone(didCrash = true)

        val json = telemetry.single("render_process_gone")
        assertEquals(setOf("envelope", "creative_type", "did_crash", "was_shown"), json.keySet())
        assertEnvelope(json.envelope, "render_process_gone", rate = 0.125)
        assertEquals("html", json.getString("creative_type"))
        assertEquals(true, json.get("did_crash"))
        assertEquals(false, json.get("was_shown"))
    }

    // rule 3

    @Test
    fun `every event encodes as one UTF-8 JSON object and nothing after it`() {
        val auctionId = "аукціон-𓀀"
        val events = adEvents(auctionId = auctionId)

        events.rendered()
        events.loadFailed(LoadFailure.MARKUP_REJECTED)
        events.processGone(didCrash = false)

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
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

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
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)

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
        assertEquals(2_000_000_000_007L, telemetry.single("creative_rendered").envelope.getLong("event_ts"))

        now = 2_100_000_000_000L
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        assertEquals(2_100_000_000_007L, telemetry.single("creative_load_failed").envelope.getLong("event_ts"))

        now = 2_200_000_000_000L
        events.processGone(didCrash = false)
        assertEquals(2_200_000_000_007L, telemetry.single("render_process_gone").envelope.getLong("event_ts"))
    }

    @Test
    fun `a wall clock moved backwards is written as it reads`() {
        val events = adEvents()
        now = 5L

        events.rendered()

        assertEquals(5L, telemetry.single("creative_rendered").envelope.getLong("event_ts"))
    }

    // rule 5, rule 25 (blank is Kotlin's isBlank)

    @Test
    fun `the auction id goes out exactly as given, surrounding spaces included`() {
        listOf(AUCTION, " a ", "\ta", "аукціон", "x".repeat(5_000)).forEach { auctionId ->
            val recording = RecordingTelemetry()
            val events = adEvents(auctionId = auctionId, telemetry = recording)

            events.rendered()
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

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
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

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
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

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
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)

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
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit -> assertFalse("${emit.name} $adType", emit.json().envelope.has("ad_format")) }
        }
    }

    // rule 8

    @Test
    fun `every event carries the session it was made with`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)

        assertEquals(3, telemetry.emitted.size)
        telemetry.emitted.forEach { emit ->
            assertEquals(emit.name, session.value, emit.json().envelope.getString("session_id"))
        }
    }

    // rule 9

    @Test
    fun `every event carries the creative type in its wire spelling`() {
        Creative.entries.forEach { creative ->
            val recording = RecordingTelemetry()
            val events = adEvents(creative = creative, telemetry = recording)

            events.rendered()
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            events.processGone(didCrash = true)

            assertEquals(3, recording.emitted.size)
            recording.emitted.forEach { emit ->
                assertEquals("${emit.name} $creative", creative.wireName, emit.json().getString("creative_type"))
            }
        }
        assertEquals(listOf("html", "mraid", "vast"), Creative.entries.map { it.wireName })
    }

    // rule 10

    @Test
    fun `the load time runs from the first load start, not a later one`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()
        elapsed = 200
        events.loadStarted()
        elapsed = 350
        events.loaded()

        events.rendered()

        assertJsonInteger(250L, telemetry.single("creative_rendered"), "load_ms")
    }

    @Test
    fun `the load time runs to the first load end, not a later one`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()
        elapsed = 350
        events.loaded()
        elapsed = 900
        events.loaded()
        elapsed = 1_500

        events.rendered()

        assertJsonInteger(250L, telemetry.single("creative_rendered"), "load_ms")
    }

    @Test
    fun `the load time is read from the monotonic clock, not the wall clock`() {
        val events = adEvents()
        elapsed = 100
        now = 9_000_000
        events.loadStarted()
        elapsed = 140
        now = 1_000
        events.loaded()

        events.rendered()

        assertJsonInteger(40L, telemetry.single("creative_rendered"), "load_ms")
    }

    @Test
    fun `a load that took no measurable time is written as zero`() {
        val events = adEvents()
        elapsed = 777
        events.loadStarted()
        events.loaded()

        events.rendered()

        assertJsonInteger(0L, telemetry.single("creative_rendered"), "load_ms")
    }

    @Test
    fun `a load that took longer than an Int holds keeps its full length`() {
        val events = adEvents()
        elapsed = 0
        events.loadStarted()
        elapsed = 3_000_000_000L
        events.loaded()

        events.rendered()

        assertJsonInteger(3_000_000_000L, telemetry.single("creative_rendered"), "load_ms")
    }

    @Test
    fun `there is no load time without a load start`() {
        val events = adEvents()
        events.loaded()

        events.rendered()

        assertEquals(setOf("envelope", "creative_type"), telemetry.single("creative_rendered").keySet())
    }

    @Test
    fun `there is no load time without a load end`() {
        val events = adEvents()
        events.loadStarted()
        elapsed += 50

        events.rendered()

        assertEquals(setOf("envelope", "creative_type"), telemetry.single("creative_rendered").keySet())
    }

    @Test
    fun `there is no load time when neither end of the load was seen`() {
        adEvents().rendered()

        assertEquals(setOf("envelope", "creative_type"), telemetry.single("creative_rendered").keySet())
    }

    @Test
    fun `there is no load time when the load ended before it started`() {
        val events = adEvents()
        elapsed = 100
        events.loaded()
        elapsed = 200
        events.loadStarted()

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    // rule 25: the first loaded() is the first call ever

    @Test
    fun `a load end before the first start spoils the load time even when another end follows`() {
        val events = adEvents()
        elapsed = 100
        events.loaded()
        elapsed = 200
        events.loadStarted()
        elapsed = 300
        events.loaded()

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    @Test
    fun `there is no load time when the clock reads earlier at the end than at the start`() {
        val events = adEvents()
        elapsed = 500
        events.loadStarted()
        elapsed = 300
        events.loaded()

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    // rule 26

    @Test
    fun `a load end before the start leaves the load time out even when both read the same instant`() {
        val events = adEvents()
        elapsed = 500
        events.loaded()
        events.loadStarted()

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    // rule 27

    @Test
    fun `a whole load that lands between the render and the capture adds no load time`() {
        val events = adEvents()
        telemetry.beforeCapture = {
            elapsed = 100
            events.loadStarted()
            elapsed = 150
            events.loaded()
        }

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    @Test
    fun `a load end that lands between the render and the capture adds no load time`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()
        telemetry.beforeCapture = {
            elapsed = 150
            events.loaded()
        }

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    @Test
    fun `a load start or end that lands between the render and the capture leaves the load time as it was`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()
        elapsed = 250
        events.loaded()
        telemetry.beforeCapture = {
            elapsed = 900
            events.loadStarted()
            events.loaded()
        }

        events.rendered()

        assertJsonInteger(150L, telemetry.single("creative_rendered"), "load_ms")
    }

    // rule 22

    @Test
    fun `there is no load time when the difference overflows from the top of the clock to the bottom`() {
        val events = adEvents()
        elapsed = Long.MAX_VALUE
        events.loadStarted()
        elapsed = Long.MIN_VALUE
        events.loaded()

        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    @Test
    fun `there is no load time when the difference overflows from the bottom of the clock to the top`() {
        listOf(Long.MIN_VALUE to Long.MAX_VALUE, -1L to Long.MAX_VALUE, Long.MIN_VALUE to 1L).forEach { (start, end) ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            elapsed = start
            events.loadStarted()
            elapsed = end
            events.loaded()

            events.rendered()

            assertFalse("$start..$end", recording.single("creative_rendered").has("load_ms"))
        }
    }

    @Test
    fun `the widest load time that fits in a Long is still written`() {
        val events = adEvents()
        elapsed = 0
        events.loadStarted()
        elapsed = Long.MAX_VALUE
        events.loaded()

        events.rendered()

        assertJsonInteger(Long.MAX_VALUE, telemetry.single("creative_rendered"), "load_ms")
    }

    // rule 10, rule 25

    @Test
    fun `a load start or end after the render does not change what was emitted`() {
        val events = adEvents()
        elapsed = 100
        events.loadStarted()

        events.rendered()
        elapsed = 400
        events.loaded()
        events.rendered()

        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
        assertEquals(1, telemetry.emitted.size)
    }

    @Test
    fun `only creative_rendered carries the load time`() {
        val events = adEvents()
        events.loadStarted()
        elapsed += 20
        events.loaded()

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)

        assertFalse(telemetry.single("creative_load_failed").has("load_ms"))
        assertFalse(telemetry.single("render_process_gone").has("load_ms"))
    }

    // rule 11, rule 25 (integers are JSON integers)

    @Test
    fun `every load failure reason goes out in its wire spelling`() {
        LoadFailure.entries.forEach { reason ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(reason)

            assertEquals(reason.wireName, recording.single("creative_load_failed").getString("reason"))
        }
        assertEquals(
            listOf("webview_error", "markup_rejected", "vast_error", "no_media_file", "playback_error"),
            LoadFailure.entries.map { it.wireName },
        )
    }

    @Test
    fun `any code goes out as an integer, zero and negative ones included`() {
        listOf(Int.MIN_VALUE, -6, -1, 0, 1, 404, Int.MAX_VALUE).forEach { code ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).loadFailed(LoadFailure.WEBVIEW_ERROR, code)

            assertJsonInteger(code.toLong(), recording.single("creative_load_failed"), "code")
        }
    }

    @Test
    fun `a failure without a code leaves the code out`() {
        adEvents().loadFailed(LoadFailure.VAST_ERROR)

        assertEquals(
            setOf("envelope", "creative_type", "reason"),
            telemetry.single("creative_load_failed").keySet(),
        )
    }

    @Test
    fun `a failure with an explicit null code leaves the code out`() {
        adEvents().loadFailed(LoadFailure.PLAYBACK_ERROR, null)

        assertFalse(telemetry.single("creative_load_failed").has("code"))
    }

    // rule 12

    @Test
    fun `the crash flag goes out as a JSON boolean, as passed`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()

            adEvents(telemetry = recording).processGone(didCrash = didCrash)

            assertEquals("did_crash for $didCrash", didCrash, recording.single("render_process_gone").get("did_crash"))
        }
    }

    @Test
    fun `a process death before any render was not shown`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            events.loadStarted()
            events.loaded()
            events.loadFailed(LoadFailure.WEBVIEW_ERROR)

            events.processGone(didCrash = didCrash)

            assertEquals("was_shown for $didCrash", false, recording.single("render_process_gone").get("was_shown"))
        }
    }

    @Test
    fun `a process death after a render was shown`() {
        listOf(false, true).forEach { didCrash ->
            val recording = RecordingTelemetry()
            val events = adEvents(telemetry = recording)
            events.rendered()

            events.processGone(didCrash = didCrash)

            val json = recording.single("render_process_gone")
            assertEquals("was_shown for $didCrash", true, json.get("was_shown"))
            assertEquals("did_crash for $didCrash", didCrash, json.get("did_crash"))
        }
    }

    @Test
    fun `a render that telemetry did not keep still counts as shown`() {
        val events = adEvents()
        telemetry.rate = null
        events.rendered()
        telemetry.rate = SamplingRate.ALL

        events.processGone(didCrash = true)

        assertEquals(true, telemetry.single("render_process_gone").get("was_shown"))
    }

    @Test
    fun `a render after the process death was reported does not make it shown`() {
        val events = adEvents()

        events.processGone(didCrash = false)
        events.rendered()

        assertEquals(false, telemetry.single("render_process_gone").get("was_shown"))
    }

    // rule 30

    @Test
    fun `a render that lands while the process death is being emitted does not make it shown`() {
        val events = adEvents()
        telemetry.beforeCapture = { events.rendered() }

        events.processGone(didCrash = false)

        assertEquals(false, telemetry.single("render_process_gone").get("was_shown"))
        assertEquals(
            setOf("render_process_gone" to EventClass.ERROR, "creative_rendered" to EventClass.DIAGNOSTIC),
            telemetry.calls().toSet(),
        )
    }

    // rule 13

    @Test
    fun `only the first render emits`() {
        val events = adEvents()

        repeat(5) { events.rendered() }

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
    }

    // rule 13, rule 25: calls after the first rendered() change nothing

    @Test
    fun `a later render with a load time does not emit either`() {
        val events = adEvents()
        events.rendered()
        events.loadStarted()
        elapsed += 10
        events.loaded()

        events.rendered()

        assertEquals(1, telemetry.emitted.size)
        assertFalse(telemetry.single("creative_rendered").has("load_ms"))
    }

    // rule 14

    @Test
    fun `only the first load failure emits, and it keeps its own reason and code`() {
        val events = adEvents()

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.loadFailed(LoadFailure.PLAYBACK_ERROR, 7)
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.loadFailed(LoadFailure.VAST_ERROR, 0)

        assertEquals(listOf("creative_load_failed" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("creative_load_failed")
        assertEquals("webview_error", json.getString("reason"))
        assertFalse(json.has("code"))
    }

    @Test
    fun `a first failure with a code is not replaced by a later one without`() {
        val events = adEvents()

        events.loadFailed(LoadFailure.NO_MEDIA_FILE, -3)
        events.loadFailed(LoadFailure.MARKUP_REJECTED)

        val json = telemetry.single("creative_load_failed")
        assertEquals("no_media_file", json.getString("reason"))
        assertJsonInteger(-3L, json, "code")
    }

    // rule 15

    @Test
    fun `only the first process death emits, and it keeps its own crash flag`() {
        val events = adEvents()

        events.processGone(didCrash = true)
        events.processGone(didCrash = false)
        events.processGone(didCrash = true)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
        val json = telemetry.single("render_process_gone")
        assertEquals(true, json.get("did_crash"))
        assertEquals(false, json.get("was_shown"))
    }

    // rule 16

    @Test
    fun `a load failure after the render process died does not emit`() {
        val events = adEvents()

        events.processGone(didCrash = true)
        events.loadFailed(LoadFailure.WEBVIEW_ERROR, -1)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a load failure before the render process died does not stop the death being reported`() {
        val events = adEvents()

        events.loadFailed(LoadFailure.WEBVIEW_ERROR, -1)
        events.processGone(didCrash = false)

        assertEquals(
            listOf("creative_load_failed" to EventClass.ERROR, "render_process_gone" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    // rule 16, rule 18, rule 25

    @Test
    fun `a process death that was not kept still stops a later load failure`() {
        val events = adEvents()
        telemetry.rate = null
        events.processGone(didCrash = true)
        telemetry.rate = SamplingRate.ALL

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
    }

    // rule 17

    @Test
    fun `a failure after the render is still reported`() {
        val events = adEvents()

        events.rendered()
        events.loadFailed(LoadFailure.PLAYBACK_ERROR, 2)

        assertEquals(
            listOf("creative_rendered" to EventClass.DIAGNOSTIC, "creative_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `a render after a failure is still reported`() {
        val events = adEvents()

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.rendered()

        assertEquals(
            listOf("creative_load_failed" to EventClass.ERROR, "creative_rendered" to EventClass.DIAGNOSTIC),
            telemetry.calls(),
        )
    }

    // rule 23

    @Test
    fun `a render after the render process died is still reported`() {
        val events = adEvents()

        events.processGone(didCrash = true)
        events.rendered()

        assertEquals(
            listOf("render_process_gone" to EventClass.ERROR, "creative_rendered" to EventClass.DIAGNOSTIC),
            telemetry.calls(),
        )
    }

    @Test
    fun `a render before the render process died does not stop the death being reported`() {
        val events = adEvents()

        events.rendered()
        events.processGone(didCrash = true)

        assertEquals(
            listOf("creative_rendered" to EventClass.DIAGNOSTIC, "render_process_gone" to EventClass.ERROR),
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

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `a load failure that telemetry did not keep still uses up the load failure`() {
        val events = adEvents()
        telemetry.rate = null
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        telemetry.rate = SamplingRate.ALL

        events.loadFailed(LoadFailure.PLAYBACK_ERROR, 3)

        assertEquals(listOf("creative_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `a process death that telemetry did not keep still uses up the process death`() {
        val events = adEvents()
        telemetry.rate = null
        events.processGone(didCrash = true)
        telemetry.rate = SamplingRate.ALL

        events.processGone(didCrash = false)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
        assertEquals(0, telemetry.captures)
    }

    @Test
    fun `when telemetry keeps nothing, a second round of every call emits nothing`() {
        val events = adEvents()
        telemetry.rate = null
        events.rendered()
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)
        telemetry.rate = SamplingRate.ALL

        events.rendered()
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.processGone(didCrash = true)

        assertEquals(
            listOf(
                "creative_rendered" to EventClass.DIAGNOSTIC,
                "creative_load_failed" to EventClass.ERROR,
                "render_process_gone" to EventClass.ERROR,
            ),
            telemetry.calls(),
        )
        assertEquals(0, telemetry.captures)
    }

    // rule 19

    @Test
    fun `renders racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, _ -> events.rendered() }
            .forEach { calls -> assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), calls) }
    }

    @Test
    fun `load failures racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, index -> events.loadFailed(LoadFailure.entries[index % 5], index) }
            .forEach { calls -> assertEquals(listOf("creative_load_failed" to EventClass.ERROR), calls) }
    }

    @Test
    fun `process deaths racing on many threads emit once`() {
        race(rounds = ROUNDS) { events, index -> events.processGone(index % 2 == 0) }
            .forEach { calls -> assertEquals(listOf("render_process_gone" to EventClass.ERROR), calls) }
    }

    @Test
    fun `a process death racing load failures emits once, with at most one load failure`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            if (index % 2 == 0) events.processGone(didCrash = true)
            else events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        }

        outcomes.forEach { calls ->
            assertEquals(calls.toString(), 1, calls.count { it.first == "render_process_gone" })
            assertTrue(calls.toString(), calls.count { it.first == "creative_load_failed" } <= 1)
            assertEquals(calls.toString(), calls.size, calls.count { it.first in RACED_NAMES })
        }
    }

    @Test
    fun `every kind of call racing on many threads emits each event at most once`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            when (index % 5) {
                0 -> events.rendered()
                1 -> events.loadFailed(LoadFailure.PLAYBACK_ERROR)
                2 -> events.processGone(didCrash = false)
                3 -> events.loadStarted()
                else -> events.loaded()
            }
        }

        outcomes.forEach { calls ->
            assertEquals(calls.toString(), 1, calls.count { it.first == "creative_rendered" })
            assertEquals(calls.toString(), 1, calls.count { it.first == "render_process_gone" })
            assertTrue(calls.toString(), calls.count { it.first == "creative_load_failed" } <= 1)
        }
    }

    // rule 19, rule 21, rule 24: a second call made while the first is still inside emit, from another thread.

    @Test
    fun `a render made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.rendered() } }

        events.rendered()

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
    }

    @Test
    fun `a load failure made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.loadFailed(LoadFailure.PLAYBACK_ERROR, 1) } }

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)

        assertEquals(listOf("creative_load_failed" to EventClass.ERROR), telemetry.calls())
        assertEquals("webview_error", telemetry.single("creative_load_failed").getString("reason"))
    }

    @Test
    fun `a process death made while the first one is being emitted neither emits nor waits for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.processGone(didCrash = false) } }

        events.processGone(didCrash = true)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a load failure made while a process death is being emitted lost the race, so it does not emit`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.loadFailed(LoadFailure.WEBVIEW_ERROR) } }

        events.processGone(didCrash = true)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `a process death made while a load failure is being emitted still emits, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.processGone(didCrash = true) } }

        events.loadFailed(LoadFailure.WEBVIEW_ERROR)

        assertEquals(
            setOf("creative_load_failed" to EventClass.ERROR, "render_process_gone" to EventClass.ERROR),
            telemetry.calls().toSet(),
        )
        assertEquals(2, telemetry.emitted.size)
    }

    @Test
    fun `a render made while a failure is being emitted still emits, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.rendered() } }

        events.loadFailed(LoadFailure.PLAYBACK_ERROR)

        assertEquals(
            setOf("creative_load_failed" to EventClass.ERROR, "creative_rendered" to EventClass.DIAGNOSTIC),
            telemetry.calls().toSet(),
        )
    }

    @Test
    fun `a load start or end made while an event is being emitted does not wait for it`() {
        val events = adEvents()
        telemetry.whileEmitting = {
            onAnotherThread {
                events.loadStarted()
                events.loaded()
            }
        }

        events.rendered()

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
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
        events.loadFailed(LoadFailure.PLAYBACK_ERROR)

        events.closed()
        events.closed()

        assertEquals(
            listOf("creative_rendered" to EventClass.DIAGNOSTIC, "creative_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `a process death after the ad was closed emits nothing`() {
        val events = adEvents()
        events.rendered()
        events.closed()

        events.processGone(didCrash = true)
        events.processGone(didCrash = false)

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
    }

    @Test
    fun `a process death after the ad was closed still stops a later load failure`() {
        val events = adEvents()
        events.closed()

        events.processGone(didCrash = true)
        events.loadFailed(LoadFailure.WEBVIEW_ERROR, -1)

        assertEquals(emptyList<Pair<String, EventClass>>(), telemetry.calls())
    }

    @Test
    fun `a process death before the ad was closed is still reported, once`() {
        val events = adEvents()

        events.processGone(didCrash = true)
        events.closed()
        events.processGone(didCrash = false)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
        assertEquals(true, telemetry.single("render_process_gone").get("did_crash"))
    }

    @Test
    fun `closing the ad does not stop a render or a load failure`() {
        val events = adEvents()
        events.closed()

        events.rendered()
        events.loadFailed(LoadFailure.VAST_ERROR, 3)

        assertEquals(
            listOf("creative_rendered" to EventClass.DIAGNOSTIC, "creative_load_failed" to EventClass.ERROR),
            telemetry.calls(),
        )
    }

    @Test
    fun `closing the ad does not use up the render or the load failure`() {
        val events = adEvents()
        events.loadFailed(LoadFailure.WEBVIEW_ERROR)
        events.closed()
        events.rendered()
        events.closed()

        events.rendered()
        events.loadFailed(LoadFailure.PLAYBACK_ERROR)

        assertEquals(
            listOf("creative_load_failed" to EventClass.ERROR, "creative_rendered" to EventClass.DIAGNOSTIC),
            telemetry.calls(),
        )
    }

    // rule 29

    @Test
    fun `a process death being emitted when the ad is closed on another thread is still emitted once, without waiting`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.closed() } }

        events.processGone(didCrash = true)

        assertEquals(listOf("render_process_gone" to EventClass.ERROR), telemetry.calls())
    }

    @Test
    fun `closing the ad while a render is being emitted does not wait for it`() {
        val events = adEvents()
        telemetry.whileEmitting = { onAnotherThread { events.closed() } }

        events.rendered()

        assertEquals(listOf("creative_rendered" to EventClass.DIAGNOSTIC), telemetry.calls())
    }

    @Test
    fun `process deaths racing a close emit at most one death, and no other event`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            if (index % 2 == 0) events.closed() else events.processGone(didCrash = true)
        }

        outcomes.forEach { calls ->
            assertTrue(calls.toString(), calls.size <= 1)
            assertTrue(calls.toString(), calls.all { it == ("render_process_gone" to EventClass.ERROR) })
        }
    }

    @Test
    fun `a close racing process deaths and load failures emits at most one of each, and no other event`() {
        val outcomes = race(rounds = ROUNDS) { events, index ->
            when (index % 4) {
                0 -> events.closed()
                1, 2 -> events.processGone(didCrash = false)
                else -> events.loadFailed(LoadFailure.WEBVIEW_ERROR)
            }
        }

        outcomes.forEach { calls ->
            assertTrue(calls.toString(), calls.count { it.first == "render_process_gone" } <= 1)
            assertTrue(calls.toString(), calls.count { it.first == "creative_load_failed" } <= 1)
            assertTrue(calls.toString(), calls.all { it.first in RACED_NAMES })
        }
    }

    // rule 20

    @Test
    fun `no call throws with telemetry switched off, in any order`() {
        val calls: List<(AdEvents) -> Unit> = listOf(
            { it.loadStarted() },
            { it.loaded() },
            { it.rendered() },
            { it.loadFailed(LoadFailure.WEBVIEW_ERROR, Int.MIN_VALUE) },
            { it.processGone(didCrash = true) },
            { it.closed() },
        )
        permutations(calls).forEach { order ->
            val events = adEvents(telemetry = Telemetry.Disabled)
            repeat(2) { order.forEach { call -> call(events) } }
        }
    }

    // rule 20, rule 22

    @Test
    fun `capturing and encoding never throw for extreme clock readings, and an overflowing load time is left out`() {
        val readings = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        readings.forEach { wall ->
            readings.forEach { start ->
                readings.forEach { end ->
                    val recording = RecordingTelemetry()
                    now = wall
                    val events = adEvents(telemetry = recording)
                    elapsed = start
                    events.loadStarted()
                    elapsed = end
                    events.loaded()

                    events.rendered()
                    events.loadFailed(LoadFailure.WEBVIEW_ERROR, Int.MAX_VALUE)
                    events.processGone(didCrash = true)

                    assertEquals(3, recording.emitted.size)
                    recording.emitted.forEach { emit -> assertEquals(wall, emit.json().envelope.getLong("event_ts")) }
                    val expected = runCatching { Math.subtractExact(end, start) }.getOrNull()?.takeIf { it >= 0 }
                    val rendered = recording.single("creative_rendered")
                    if (expected != null) {
                        assertJsonInteger(expected, rendered, "load_ms")
                    } else {
                        assertFalse("$start..$end", rendered.has("load_ms"))
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
                Creative.entries.forEach { creative ->
                    val recording = RecordingTelemetry()
                    val events = adEvents(auctionId, adType, creative, recording)

                    events.rendered()
                    events.loadFailed(LoadFailure.MARKUP_REJECTED)
                    events.processGone(didCrash = false)

                    assertEquals(3, recording.emitted.size)
                    recording.emitted.forEach { assertNotNull(it.event!!.encode()) }
                }
            }
        }
    }

    // Helpers

    /**
     * Runs [THREADS] calls of [call] at once on a fresh [AdEvents], [rounds] times, and returns what each round
     * emitted. The threads are released together by a latch once all of them are waiting on it.
     */
    private fun race(rounds: Int, call: (AdEvents, Int) -> Unit): List<List<Pair<String, EventClass>>> {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            return List(rounds) {
                val recording = RecordingTelemetry()
                val events = adEvents(telemetry = recording)
                elapsed = 0
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

    private val JSONObject.envelope get() = getJSONObject("envelope")

    private fun assertEnvelope(json: JSONObject, name: String, rate: Double) {
        assertEquals(ENVELOPE_KEYS, json.keySet())
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

        val RACED_NAMES = setOf("render_process_gone", "creative_load_failed")
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
