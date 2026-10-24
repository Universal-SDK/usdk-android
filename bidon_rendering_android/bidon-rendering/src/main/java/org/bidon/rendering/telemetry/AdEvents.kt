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

/**
 * The renderer's telemetry events of one ad: `ad_impression`, `ad_load_failed` and `render_process_gone`.
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

    // Only ever compared with null, so the identity comparison of compareAndSet is exact.
    private val showStart = AtomicReference<Long?>()
    private val viewStart = AtomicReference<Long?>()
    private val loadStart = AtomicReference<Long?>()
    private val renderReported = AtomicBoolean()

    /** Taken by the first process death, or by [closed], after which a death is not reported. */
    private val processGoneReported = AtomicBoolean()

    /** Taken by the first load failure, or closed by a process death before any load failure. */
    private val loadFailureReported = AtomicBoolean()

    /** The host asked for the ad to be shown on a full screen. Only the first call counts. */
    fun showRequested() {
        showStart.compareAndSet(null, monotonicClock.elapsedMillis())
    }

    /** The host asked for the ad's view. Only the first call counts. */
    fun viewRequested() {
        viewStart.compareAndSet(null, monotonicClock.elapsedMillis())
    }

    /** The creative started loading. Only the first call counts. */
    fun loadStarted() {
        loadStart.compareAndSet(null, monotonicClock.elapsedMillis())
    }

    /**
     * The creative showed its first content: emits `ad_impression`, once per ad.
     *
     * `render_duration_ms` is the time from the first [showRequested], or the first [viewRequested] when the ad was
     * never asked to show on a full screen, to this call, on the monotonic clock. It is left out when neither was
     * called before this one, or the clock gives no sensible difference. `om_enabled` is always `false`: the renderer
     * does not measure viewability yet.
     */
    fun rendered() {
        if (!renderReported.compareAndSet(false, true)) return
        val renderMillis = elapsedSince(showStart.get() ?: viewStart.get())
        emit(EventName.AD_IMPRESSION, EventClass.COUNTED) {
            if (renderMillis != null) put("render_duration_ms", renderMillis)
            put("om_enabled", false)
        }
    }

    /**
     * The creative could not be loaded or played: emits `ad_load_failed` with [code]'s number, domain and load stage,
     * unless an earlier failure or the death of the render process was reported first. `duration_ms` is the time
     * since [loadStarted], sent only for a code with a load stage.
     *
     * @param externalCode what the WebView, the server or the player said, kept as it came, with [code]'s external
     * source; dropped for a code that has none
     * @param cause the exception behind the failure; only its class names are sent
     */
    fun loadFailed(code: ErrorCode, externalCode: String? = null, cause: Throwable? = null) {
        if (!loadFailureReported.compareAndSet(false, true)) return
        // A failure with no load stage came after the load, so the time since its start is not a load time.
        val durationMillis = if (code.stage != null) elapsedSince(loadStart.get()) else null
        val classes = cause?.let(ExceptionClasses::of)
        emit(EventName.AD_LOAD_FAILED, EventClass.ERROR) {
            put("error_code", code.number.toString())
            put("error_domain", code.domain.wireName)
            if (code.stage != null) put("failed_stage", code.stage.wireName)
            if (durationMillis != null) put("duration_ms", durationMillis)
            if (externalCode != null && code.externalSource != null) {
                put("external_error_code", externalCode)
                put("external_error_source", code.externalSource.wireName)
            }
            classes?.exceptionClass?.let { put("exception_class", it) }
            classes?.rootCauseClass?.let { put("exception_cause_class", it) }
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
        emit(EventName.RENDER_PROCESS_GONE, EventClass.ERROR) {
            put("creative_type", creative.wireName)
            put("did_crash", didCrash)
            put("was_shown", wasShown)
        }
    }

    /** The ad was closed: a later death of the shared render process is not reported for it. */
    fun closed() {
        processGoneReported.set(true)
    }

    /**
     * The monotonic time from [start] to now, or `null` when there is no start, it reads later than now, or the clock
     * gives no sensible difference.
     */
    private fun elapsedSince(start: Long?): Long? {
        if (start == null) return null
        val millis = try {
            Math.subtractExact(monotonicClock.elapsedMillis(), start)
        } catch (_: ArithmeticException) {
            return null
        }
        return millis.takeIf { it >= 0 }
    }

    /** Emits [name], its envelope captured when telemetry keeps it, with the fields [fields] adds. */
    private fun emit(name: EventName, eventClass: EventClass, fields: JSONObject.() -> Unit) {
        telemetry.emit(name.wireName, eventClass) { rate ->
            val envelope = EventEnvelope.capture(
                name,
                sessionId,
                clock,
                auctionId = auctionId?.takeIf { it.isNotBlank() },
                adType = adType,
                samplingRate = rate,
            )
            val json = envelope.eventJson().apply(fields)
            AdEvent(json, eventClass)
        }
    }

    private class AdEvent(private val json: JSONObject, override val eventClass: EventClass) : TelemetryEvent {
        override fun encode(): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    }
}
