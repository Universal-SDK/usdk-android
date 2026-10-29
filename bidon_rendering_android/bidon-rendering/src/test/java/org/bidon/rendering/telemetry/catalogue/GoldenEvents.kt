package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.AdType
import org.bidon.rendering.telemetry.Clock
import org.bidon.rendering.telemetry.CrashReport
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.ErrorCode
import org.bidon.rendering.telemetry.EventName
import org.bidon.rendering.telemetry.MonotonicClock
import org.bidon.rendering.telemetry.ProcessExit
import org.bidon.rendering.telemetry.RendererPriority
import org.bidon.rendering.telemetry.SamplingRate
import org.bidon.rendering.telemetry.SessionId
import org.bidon.rendering.telemetry.Telemetry
import org.bidon.rendering.telemetry.TelemetryEvent
import java.io.IOException

/**
 * Each event the SDK emits, encoded by its production encoder (`AdEvents`, `CrashReport`) with fixed clocks, a fixed
 * session and auction id, and every optional field the event can carry present. Only `envelope.event_id` differs from
 * run to run: it is a fresh UUID v4.
 */
internal object GoldenEvents {

    /** The encoded text of [name], as the encoder wrote it. */
    fun encode(name: EventName): String = when (name) {
        EventName.AD_IMPRESSION -> adEvent(name) { advance ->
            showRequested()
            advance(RENDER_MILLIS)
            rendered()
        }
        EventName.AD_LOAD_FAILED -> adEvent(name) { advance ->
            loadStarted()
            advance(LOAD_MILLIS)
            loadFailed(
                ErrorCode.VAST_WRAPPER_HTTP_STATUS,
                "404",
                IllegalStateException("never sent", IOException("never sent")),
            )
        }
        EventName.RENDER_CRASHED -> adEvent(name) {
            rendered()
            processGone(ProcessExit(didCrash = true, priorityAtExit = RendererPriority.IMPORTANT))
        }
        EventName.SDK_CRASHED -> String(
            CrashReport(listOf("org.bidon.rendering."), SESSION, CLOCK).encode(crash(), onMainThread = true)!!,
            Charsets.UTF_8,
        )
    }

    /** Where the checked-in sample of [name] lives, as a classpath resource. */
    fun samplePath(name: EventName): String = "telemetry/golden/${name.wireName}.json"

    private val SESSION = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!
    private const val AUCTION = "8b0f3c2e-5d4a-4e1b-9c7f-2a6d8e1b3f50"
    private const val NOW = 1_790_326_698_635L
    private const val RENDER_MILLIS = 420L
    private const val LOAD_MILLIS = 420L
    private val CLOCK = Clock { NOW }

    /**
     * Runs [actions] on a fresh ad's events and returns the one event named [name] they emitted. [actions] gets a
     * function that moves the ad's monotonic clock forward.
     */
    private fun adEvent(name: EventName, actions: AdEvents.(advance: (Long) -> Unit) -> Unit): String {
        val emitted = mutableMapOf<String, String>()
        val telemetry = object : Telemetry {
            override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
                emitted[eventName] = String(capture(SamplingRate.ALL).encode(), Charsets.UTF_8)
            }

            override fun sync() = Unit
        }
        var elapsed = 10_000L
        AdEvents(telemetry, SESSION, AUCTION, AdType.INTERSTITIAL, Renderer.HTML, CLOCK, MonotonicClock { elapsed })
            .actions { elapsed += it }
        return emitted.getValue(name.wireName)
    }

    /** A crash in our code with a cause from outside it, with stack traces fixed so the sample is stable. */
    private fun crash(): Throwable {
        val cause = IllegalArgumentException("never sent").apply {
            stackTrace = arrayOf(
                StackTraceElement("android.webkit.WebView", "loadUrl", "WebView.java", 1046),
                StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106),
            )
        }
        return IllegalStateException("never sent", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement("android.view.View", "performClick", "View.java", 7659),
                StackTraceElement("org.bidon.rendering.html.ClickHandler", "shouldOverrideUrlLoading", "ClickHandler.kt", 60),
                StackTraceElement("org.bidon.rendering.html.HtmlRenderer", "onClick", "HtmlRenderer.kt", 58),
                StackTraceElement("android.os.Looper", "loop", "Looper.java", 288),
                StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177),
            )
        }
    }
}
