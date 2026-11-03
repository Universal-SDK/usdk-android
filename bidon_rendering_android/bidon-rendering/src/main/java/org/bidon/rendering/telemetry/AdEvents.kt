package org.bidon.rendering.telemetry

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The renderer that drew an ad, as it is written on the wire. */
internal enum class Renderer(val wireName: String) {
    HTML("html"),
    MRAID("mraid"),
    VAST("vast"),
}

/**
 * The renderer's telemetry events of one ad: `ad_show_requested`, `ad_impression`, `ad_show_failed`, `ad_load_failed`
 * and `render_crashed`.
 *
 * Every show request is reported and opens a show, which ends in at most one terminal: the impression, which an ad
 * has once, or a show failure. The other events are reported once per ad, a load failure only before a show started,
 * and a death of the render process only while the ad is open. All of this holds whether or not telemetry
 * keeps the events. A call takes its place before it emits and holds no lock while it does, so a second call never
 * waits for the first.
 *
 * Safe to call from any thread, and never throws.
 *
 * @param auctionId the auction the ad won; a blank one is left out of the events
 * @param declaredType the creative type the DSP declared, left out of the events when `null`
 * @param integration which integration created the ad's renderer; left out of the events when `null`
 */
internal class AdEvents(
    private val telemetry: Telemetry,
    private val sessionId: SessionId,
    private val auctionId: String?,
    private val adType: AdType?,
    private val integration: WireIntegration?,
    private val renderer: Renderer,
    private val declaredType: String?,
    private val clock: Clock,
    private val uptime: UptimeClock,
    private val monotonicClock: MonotonicClock,
) {

    // Only ever compared with null, so the identity comparison of compareAndSet is exact.
    private val showStart = AtomicReference<Long?>()
    private val viewStart = AtomicReference<Long?>()
    private val loadStart = AtomicReference<Long?>()
    private val loadEnd = AtomicReference<Long?>()
    private val renderReported = AtomicBoolean()

    /** The show requests that have not yet ended in an impression or a show failure. */
    private val openShows = AtomicInteger()

    /** Taken by the first process death, or by [closed], after which a death is not reported. */
    private val processGoneReported = AtomicBoolean()

    /** Taken by the first load failure, or closed by a process death or a started show before any load failure. */
    private val loadFailureReported = AtomicBoolean()

    /**
     * The host asked for the ad to be shown on a full screen: emits `ad_show_requested` on every call and opens a show.
     * The first call is the start of `render_duration_ms`.
     *
     * `time_since_loaded_ms` is the time from [loadSucceeded] to this call, on the monotonic clock. It is left out when
     * the load had not ended by then, or the clock gives no sensible difference.
     */
    fun showRequested() {
        val now = monotonicClock.elapsedMillis()
        showStart.compareAndSet(null, now)
        openShows.incrementAndGet()
        val sinceLoadedMillis = elapsedBetween(loadEnd.get(), now)
        emit(EventName.AD_SHOW_REQUESTED, EventClass.DIAGNOSTIC) {
            if (sinceLoadedMillis != null) put("time_since_loaded_ms", sinceLoadedMillis)
        }
    }

    /**
     * The show passed the renderer's ready check and is under way: a load failure is no longer reported. A show refused
     * there, before the ad loaded or after it was shown, does not hide a load failure that comes after it. Emits
     * nothing.
     */
    fun showStarted() {
        loadFailureReported.set(true)
    }

    /** The creative finished loading. Only the first call counts; emits nothing. */
    fun loadSucceeded() {
        loadEnd.compareAndSet(null, monotonicClock.elapsedMillis())
    }

    /**
     * The ad could not be shown: emits `ad_show_failed` with [code]'s number, domain and show stage, and closes the
     * open show request, if there is one.
     *
     * @param externalCode what the player said, kept as it came, with [code]'s external source
     * @param cause the exception behind the failure; only its class names are sent
     */
    fun showFailed(code: ErrorCode, externalCode: String? = null, cause: Throwable? = null) {
        if (!closeOpenShow()) return
        emitShowFailure(code, code.showStage, externalCode, cause)
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
     * The creative showed its first content: emits `ad_impression`, once per ad, and closes the open show request, if
     * there is one.
     *
     * `render_duration_ms` is the time from the first [showRequested], or the first [viewRequested] when the ad was
     * never asked to show on a full screen, to this call, on the monotonic clock. It is left out when neither was
     * called before this one, or the clock gives no sensible difference. `om_enabled` is always `false`: the renderer
     * does not measure viewability yet.
     */
    fun rendered() {
        if (!renderReported.compareAndSet(false, true)) return
        closeOpenShow()
        val renderMillis = elapsedSince(showStart.get() ?: viewStart.get())
        emit(EventName.AD_IMPRESSION, EventClass.COUNTED) {
            if (renderMillis != null) put("render_duration_ms", renderMillis)
            put("om_enabled", false)
        }
    }

    /**
     * The creative could not be loaded: emits `ad_load_failed` with [code]'s number, domain and load stage, unless an
     * earlier failure, the death of the render process or a [started show][showStarted] came first. `duration_ms` is the time since
     * [loadStarted], sent only for a code with a load stage.
     *
     * @param externalCode what the WebView or the server said, kept as it came, with [code]'s external
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
     * The WebView's render process died: emits `render_crashed` with the renderer, the declared `crtype` and what
     * [exit] knows of the cause and the priority, unless the ad was [closed] first. Only the first death of an ad is
     * reported, with its own detail. `was_shown` tells whether [rendered] had been called by then. The load failure
     * the WebView reports after a death is the same failure, so it is not reported again, even when the death itself
     * is not.
     */
    fun processGone(exit: ProcessExit) {
        val wasShown = renderReported.get()
        loadFailureReported.set(true)
        if (!processGoneReported.compareAndSet(false, true)) return
        emit(EventName.RENDER_CRASHED, EventClass.ERROR) {
            put("renderer", renderer.wireName)
            if (declaredType != null) put("crtype", declaredType)
            exit.didCrash?.let { put("did_crash", it) }
            exit.priorityAtExit?.let { put("renderer_priority_at_exit", it.wireName) }
            put("was_shown", wasShown)
        }
    }

    /**
     * The ad was closed: a later death of the shared render process is not reported for it. A show request still open
     * fails with `ad_show_failed` 7006, in the stage the ad screen had reached: the view for HTML and MRAID, the
     * playback for VAST.
     */
    fun closed() {
        processGoneReported.set(true)
        if (!closeOpenShow()) return
        val reached = if (renderer == Renderer.VAST) ShowStage.PLAYBACK_START else ShowStage.VIEW_ATTACH
        emitShowFailure(ErrorCode.CLOSED_BEFORE_IMPRESSION, reached, externalCode = null, cause = null)
    }

    /** Closes one open show request; `false` when none was open. */
    private fun closeOpenShow(): Boolean = openShows.getAndUpdate { if (it > 0) it - 1 else 0 } > 0

    private fun emitShowFailure(code: ErrorCode, stage: ShowStage?, externalCode: String?, cause: Throwable?) {
        val classes = cause?.let(ExceptionClasses::of)
        emit(EventName.AD_SHOW_FAILED, EventClass.ERROR) {
            put("error_code", code.number.toString())
            put("error_domain", code.domain.wireName)
            if (stage != null) put("failed_stage", stage.wireName)
            if (externalCode != null && code.externalSource != null) {
                put("external_error_code", externalCode)
                put("external_error_source", code.externalSource.wireName)
            }
            classes?.exceptionClass?.let { put("exception_class", it) }
            classes?.rootCauseClass?.let { put("exception_cause_class", it) }
        }
    }

    /**
     * The monotonic time from [start] to now, or `null` when there is no start, it reads later than now, or the clock
     * gives no sensible difference.
     */
    private fun elapsedSince(start: Long?): Long? = elapsedBetween(start, monotonicClock.elapsedMillis())

    /** [end] minus [start], or `null` when there is no start, [end] reads earlier, or the difference overflows. */
    private fun elapsedBetween(start: Long?, end: Long): Long? {
        if (start == null) return null
        val millis = try {
            Math.subtractExact(end, start)
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
                uptime,
                auctionId = auctionId?.takeIf { it.isNotBlank() },
                adType = adType,
                integration = integration,
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
