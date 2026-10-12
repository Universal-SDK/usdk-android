package org.bidon.rendering.telemetry

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The kind of creative an event is about, as it is written on the wire. */
internal enum class Creative(val wireName: String) {
    HTML("html"),
    MRAID("mraid"),
    VAST("vast"),
}

/** Why a creative could not be loaded or played, as it is written on the wire. */
internal enum class LoadFailure(val wireName: String) {
    /** The main frame failed to load. */
    WEBVIEW_ERROR("webview_error"),

    /** The WebView threw while the markup was loaded into it. */
    MARKUP_REJECTED("markup_rejected"),

    /** The VAST parser failed. */
    VAST_ERROR("vast_error"),

    /** No media file the device can play. */
    NO_MEDIA_FILE("no_media_file"),

    /** The player could not play the video. */
    PLAYBACK_ERROR("playback_error"),
}

/**
 * The renderer's telemetry events of one ad: `creative_rendered`, `creative_load_failed` and `render_process_gone`.
 *
 * Each event is reported once per ad, whether or not telemetry keeps it, and a death of the render process only while
 * the ad is open. A call takes its place before it emits and holds no lock while it does, so a second call never
 * waits for the first.
 *
 * Safe to call from any thread, and never throws.
 *
 * @param auctionId the auction the ad won; a blank one is left out of the events
 */
internal class AdEvents(
    private val telemetry: Telemetry,
    private val sessionId: SessionId,
    private val auctionId: String?,
    private val adType: AdType?,
    private val creative: Creative,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
) {

    private val loadStart = AtomicReference<Long?>()
    private val loadEnd = AtomicReference<LoadEnd?>()
    private val renderReported = AtomicBoolean()

    /** Taken by the first process death, or by [closed], after which a death is not reported. */
    private val processGoneReported = AtomicBoolean()

    /** Taken by the first load failure, or closed by a process death before any load failure. */
    private val loadFailureReported = AtomicBoolean()

    /** The creative started loading. Only the first call counts. */
    fun loadStarted() {
        loadStart.compareAndSet(null, monotonicClock.elapsedMillis())
    }

    /** The creative finished loading. Only the first call counts, and one before [loadStarted] leaves no load time. */
    fun loaded() {
        loadEnd.compareAndSet(null, LoadEnd(monotonicClock.elapsedMillis(), afterStart = loadStart.get() != null))
    }

    /** The creative showed its first content: emits `creative_rendered`, with the load time when there is one. */
    fun rendered() {
        if (!renderReported.compareAndSet(false, true)) return
        val loadMillis = loadMillis()
        emit(CREATIVE_RENDERED, EventClass.DIAGNOSTIC) {
            if (loadMillis != null) put("load_ms", loadMillis)
        }
    }

    /**
     * The creative could not be loaded or played: emits `creative_load_failed`, unless an earlier failure or the
     * death of the render process was reported first.
     */
    fun loadFailed(reason: LoadFailure, code: Int? = null) {
        if (!loadFailureReported.compareAndSet(false, true)) return
        emit(CREATIVE_LOAD_FAILED, EventClass.ERROR) {
            put("reason", reason.wireName)
            if (code != null) put("code", code)
        }
    }

    /**
     * The WebView's render process died: emits `render_process_gone`, unless the ad was [closed] first. `was_shown`
     * tells whether [rendered] had been called by then. The load failure the WebView reports after a death is the
     * same failure, so it is not reported again, even when the death itself is not.
     */
    fun processGone(didCrash: Boolean) {
        val wasShown = renderReported.get()
        loadFailureReported.set(true)
        if (!processGoneReported.compareAndSet(false, true)) return
        emit(RENDER_PROCESS_GONE, EventClass.ERROR) {
            put("did_crash", didCrash)
            put("was_shown", wasShown)
        }
    }

    /** The ad was closed: a later death of the shared render process is not reported for it. */
    fun closed() {
        processGoneReported.set(true)
    }

    /**
     * The first load end minus the first load start, or `null` when either is missing, they came in the wrong order,
     * or the clock gives no sensible difference.
     */
    private fun loadMillis(): Long? {
        val start = loadStart.get() ?: return null
        val end = loadEnd.get()?.takeIf { it.afterStart } ?: return null
        val millis = try {
            Math.subtractExact(end.elapsedMillis, start)
        } catch (_: ArithmeticException) {
            return null
        }
        return millis.takeIf { it >= 0 }
    }

    /** Emits [eventName], its envelope captured when telemetry keeps it, with the fields [fields] adds. */
    private fun emit(eventName: String, eventClass: EventClass, fields: JSONObject.() -> Unit) {
        telemetry.emit(eventName, eventClass) { rate ->
            val envelope = EventEnvelope.capture(
                eventName,
                sessionId,
                clock,
                auctionId = auctionId?.takeIf { it.isNotBlank() },
                adType = adType,
                samplingRate = rate,
            )
            val json = envelope.eventJson().apply {
                put("creative_type", creative.wireName)
                fields()
            }
            AdEvent(json, eventClass)
        }
    }

    /** The first load end, and whether a load start came before it. */
    private class LoadEnd(val elapsedMillis: Long, val afterStart: Boolean)

    private class AdEvent(private val json: JSONObject, override val eventClass: EventClass) : TelemetryEvent {
        override fun encode(): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val CREATIVE_RENDERED = "creative_rendered"
        const val CREATIVE_LOAD_FAILED = "creative_load_failed"
        const val RENDER_PROCESS_GONE = "render_process_gone"
    }
}
