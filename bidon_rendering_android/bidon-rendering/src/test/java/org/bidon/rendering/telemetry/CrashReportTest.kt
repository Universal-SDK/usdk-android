package org.bidon.rendering.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!
    private val clock = Clock { 1_790_000_000_000 }
    private val report = CrashReport(listOf("org.bidon.rendering.", "org.bidon.bidon."), session, clock)

    @Test
    fun `a crash with one of our frames anywhere in its stack is ours`() {
        val ours = crash(host("Game", "update"), own("vast.VastRenderer", "load"), host("Looper", "loop"))

        assertTrue(report.isOwn(ours))
        assertTrue(report.isOwn(crash(frame("org.bidon.bidon.BidonAdapter", "show"))))
    }

    @Test
    fun `a crash with none of our frames is the host app's`() {
        assertFalse(report.isOwn(crash(host("Game", "update"), host("Looper", "loop"))))
        assertFalse(report.isOwn(crash()))
    }

    @Test
    fun `the mediation SDK shares our prefix but is not ours`() {
        assertFalse(report.isOwn(crash(frame("org.bidon.sdk.BidonSdk", "loadAd"), frame("org.bidonx.Other", "run"))))
        assertFalse(report.isOwn(crash(frame("com.publisher.org.bidon.rendering.Copy", "run"))))
    }

    @Test
    fun `our frame in a cause or in a suppressed exception makes the crash ours`() {
        val viaCause = crash(host("Game", "update")).apply { initCause(crash(own("html.HtmlRenderer", "load"))) }
        val viaSuppressed = crash(host("Game", "update")).apply {
            addSuppressed(crash(own("ui.FullscreenAdActivity", "onCreate")))
        }
        val deep = crash(host("A", "a")).apply {
            initCause(crash(host("B", "b")).apply { initCause(crash(own("mraid.MraidBridge", "call"))) })
        }

        assertTrue(report.isOwn(viaCause))
        assertTrue(report.isOwn(viaSuppressed))
        assertTrue(report.isOwn(deep))
    }

    @Test
    fun `a cause chain that loops back on itself is walked once`() {
        val first = crash(host("A", "a"))
        val second = crash(host("B", "b"))
        first.initCause(second)
        second.initCause(first)

        assertFalse(report.isOwn(first))
        val exceptions = decode(report.encode(first, onMainThread = false)!!).getJSONArray("exceptions")
        assertEquals(2, exceptions.length())
    }

    @Test
    fun `the event is render_crashed, in the envelope, with the time and session of the crash`() {
        val event = decode(report.encode(crash(own("vast.VastRenderer", "load")), onMainThread = true)!!)

        val envelope = event.getJSONObject("envelope")
        assertEquals("render_crashed", envelope.getString("event_name"))
        assertEquals(session.value, envelope.getString("session_id"))
        assertEquals(1_790_000_000_000, envelope.getLong("event_ts"))
        assertTrue(envelope.getString("event_id").isNotBlank())
        assertEquals(1.0, envelope.getDouble("sampling_rate"), 0.0)
        assertTrue(event.getBoolean("crashed_on_main_thread"))
        assertFalse(envelope.has("auction_id"))
        assertFalse(envelope.has("ad_type"))
    }

    @Test
    fun `the crash's own fields sit next to the envelope, not inside it`() {
        val event = decode(report.encode(crash(own("vast.VastRenderer", "load")), onMainThread = true)!!)

        assertEquals(setOf("envelope", "crashed_on_main_thread", "exceptions"), event.keyNames())
        assertEquals(
            setOf("event_id", "event_name", "event_ts", "session_id", "sampling_rate"),
            event.getJSONObject("envelope").keyNames(),
        )
        assertTrue(event.getBoolean("crashed_on_main_thread"))
        assertTrue(event.getJSONArray("exceptions").length() > 0)
    }

    @Test
    fun `a crash off the main thread says so`() {
        val event = decode(report.encode(crash(own("vast.VastRenderer", "load")), onMainThread = false)!!)

        assertFalse(event.getBoolean("crashed_on_main_thread"))
    }

    @Test
    fun `each exception keeps its class, our frames and one neighbour on each side, and counts the rest`() {
        val crash = IllegalStateException().apply {
            stackTrace = arrayOf(
                frame("android.webkit.WebView", "loadUrl"),
                frame("java.lang.String", "charAt"),
                own("html.HtmlRenderer", "inject", 42),
                own("html.HtmlRenderer", "load", 17),
                host("AdScreen", "onResume"),
                frame("android.app.Activity", "performResume"),
                frame("android.os.Looper", "loop"),
            )
        }

        val event = decode(report.encode(crash, onMainThread = true)!!)
        val exception = event.getJSONArray("exceptions").getJSONObject(0)

        assertEquals("java.lang.IllegalStateException", exception.getString("class"))
        assertEquals(
            listOf(
                "java.lang.String.charAt(Test.kt:1)",
                "org.bidon.rendering.html.HtmlRenderer.inject(Test.kt:42)",
                "org.bidon.rendering.html.HtmlRenderer.load(Test.kt:17)",
                "com.publisher.game.AdScreen.onResume(Test.kt:1)",
            ),
            exception.getJSONArray("frames").strings(),
        )
        assertEquals(3, exception.getInt("omitted_frames"))
    }

    @Test
    fun `an exception that never passed through our code keeps its class and none of its frames`() {
        val crash = crash(own("vast.VastParser", "parse")).apply {
            initCause(
                crash(frame("org.xmlpull.Parser", "next"), host("PaymentValidator", "verify"), host("A", "a")),
            )
        }

        val cause = decode(report.encode(crash, onMainThread = false)!!).getJSONArray("exceptions").getJSONObject(1)

        assertEquals("java.lang.IllegalStateException", cause.getString("class"))
        assertEquals(emptyList<String>(), cause.getJSONArray("frames").strings())
        assertEquals(3, cause.getInt("omitted_frames"))
    }

    @Test
    fun `the crash comes first, then its causes and suppressed exceptions`() {
        val crash = IllegalStateException().apply {
            stackTrace = arrayOf(own("A", "a"))
            initCause(IllegalArgumentException().apply { stackTrace = arrayOf(own("B", "b")) })
            addSuppressed(UnsupportedOperationException().apply { stackTrace = arrayOf(own("C", "c")) })
        }

        val exceptions = decode(report.encode(crash, onMainThread = false)!!).getJSONArray("exceptions")

        assertEquals(
            listOf(
                "java.lang.IllegalStateException",
                "java.lang.IllegalArgumentException",
                "java.lang.UnsupportedOperationException",
            ),
            (0 until exceptions.length()).map { exceptions.getJSONObject(it).getString("class") },
        )
    }

    @Test
    fun `no exception message reaches the event, in the crash or in its causes`() {
        val crash = IllegalStateException("https://tracker.example/?idfa=38400000-8cf0-11bd-b23e-10b96e40000d").apply {
            stackTrace = arrayOf(own("vast.VastRenderer", "load"))
            initCause(RuntimeException("user john@example.com").apply { stackTrace = arrayOf(own("A", "a")) })
            addSuppressed(RuntimeException("token=secret").apply { stackTrace = arrayOf(own("B", "b")) })
        }

        val text = report.encode(crash, onMainThread = false)!!.decodeToString()

        listOf("tracker.example", "38400000", "john", "secret").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test
    fun `a huge stack is cut from the bottom until the event fits one queue record`() {
        val limit = EventQueue.Limits().maxRecordBytes
        val crash = IllegalStateException().apply {
            stackTrace = Array(5_000) { own("generated.${"Nested".repeat(50)}", "method$it", it) }
        }

        val bytes = report.encode(crash, onMainThread = true)!!
        val exception = decode(bytes).getJSONArray("exceptions").getJSONObject(0)
        val frames = exception.getJSONArray("frames").strings()

        assertTrue("${bytes.size} bytes", bytes.size <= limit)
        assertTrue("${frames.size} frames", frames.size in 1 until 64)
        assertEquals(
            crash.stackTrace.take(frames.size).map { "${it.className}.${it.methodName}(Test.kt:${it.lineNumber})" },
            frames,
        )
        assertEquals(5_000 - frames.size, exception.getInt("omitted_frames"))
    }

    @Test
    fun `a long chain of exceptions is described up to its first sixteen`() {
        var crash: Throwable = crash(own("A", "a"))
        repeat(100) { crash = RuntimeException(crash).apply { stackTrace = arrayOf(own("A", "a$it")) } }

        val exceptions = decode(report.encode(crash, onMainThread = false)!!).getJSONArray("exceptions")

        assertEquals(CrashReport.MAX_EXCEPTIONS, exceptions.length())
    }

    @Test
    fun `an event that cannot fit even without frames is not made`() {
        val tiny = CrashReport(listOf("org.bidon.rendering."), session, clock, maxBytes = 100)

        assertNull(tiny.encode(crash(own("A", "a")), onMainThread = false))
    }

    private fun crash(vararg frames: StackTraceElement) =
        IllegalStateException().apply { stackTrace = arrayOf(*frames) }

    private fun own(className: String, method: String, line: Int = 1) =
        frame("org.bidon.rendering.$className", method, line)

    private fun host(className: String, method: String) = frame("com.publisher.game.$className", method)

    private fun frame(className: String, method: String, line: Int = 1) =
        StackTraceElement(className, method, "Test.kt", line)

    private fun decode(bytes: ByteArray) = JSONObject(bytes.decodeToString())

    private fun org.json.JSONArray.strings() = (0 until length()).map { getString(it) }
}
