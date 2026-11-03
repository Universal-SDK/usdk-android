package org.bidon.rendering.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tells whether an uncaught exception passed through our own code, and describes it as an `sdk_crashed` event that
 * fits one queue record.
 *
 * The event says which exceptions were involved and where they passed through our code. It never carries an
 * exception's message: messages hold URLs, identifiers and the host app's data, none of which we may collect. Frames
 * outside our code are left out too, except the one on either side of each run of ours, which shows who called us and
 * what we called. An exception in the chain that never passed through our code keeps its class and no frames.
 *
 * @param ownPackages class name prefixes that mark a frame as ours, such as `org.bidon.rendering.`
 * @param integration the integration of the last renderer created in this process, read when a crash is captured, or
 * `null` when none was created or it did not say
 */
internal class CrashReport(
    private val ownPackages: List<String>,
    private val sessionId: SessionId,
    private val clock: Clock,
    private val uptime: UptimeClock,
    private val integration: () -> WireIntegration?,
    private val maxBytes: Int = EventQueue.Limits().maxRecordBytes,
) {

    /** Whether any frame of [throwable], of its causes or of its suppressed exceptions is ours. */
    fun isOwn(throwable: Throwable): Boolean = chain(throwable).any { exception -> exception.stackTrace.any(::isOwn) }

    /**
     * The event for [throwable], captured now. Frames are cut, the deepest first, until the event fits `maxBytes`;
     * `null` when it does not fit even without frames.
     */
    fun encode(throwable: Throwable, onMainThread: Boolean): ByteArray? {
        // A crash bypasses the gate and is always kept (D-031).
        val envelope = EventEnvelope.capture(
            EventName.SDK_CRASHED,
            sessionId,
            clock,
            uptime,
            integration = integration(),
            samplingRate = SamplingRate.ALL,
        )
        val exceptions = chain(throwable)
        var maxFrames = MAX_FRAMES
        while (true) {
            val event = envelope.eventJson().apply {
                put("crashed_on_main_thread", onMainThread)
                put("exceptions", JSONArray(exceptions.map { describe(it, maxFrames) }))
            }
            val bytes = event.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            if (maxFrames == 0) return null
            maxFrames /= 2
        }
    }

    private fun describe(exception: Throwable, maxFrames: Int): JSONObject {
        val trace = exception.stackTrace
        val frames = keptFrames(trace).take(maxFrames)
        return JSONObject().apply {
            put("class", exception.javaClass.name)
            put("frames", JSONArray(frames.map { it.describe() }))
            put("omitted_frames", trace.size - frames.size)
        }
    }

    /**
     * Our frames and their immediate neighbours, top of the stack first. None for an exception that never passed
     * through our code: its frames are someone else's code only, and its class is all the event needs from it.
     */
    private fun keptFrames(trace: Array<StackTraceElement>): List<StackTraceElement> {
        val own = trace.map(::isOwn)
        return trace.filterIndexed { i, _ -> own[i] || own.getOrNull(i - 1) == true || own.getOrNull(i + 1) == true }
    }

    private fun isOwn(frame: StackTraceElement): Boolean = ownPackages.any { frame.className.startsWith(it) }

    /**
     * [root], then its causes and suppressed exceptions, each exception once. A cause chain can loop back on itself,
     * and a pathological one can be very long, so the walk stops after [MAX_EXCEPTIONS].
     */
    private fun chain(root: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque(listOf(root))
        val chain = mutableListOf<Throwable>()
        while (pending.isNotEmpty() && chain.size < MAX_EXCEPTIONS) {
            val exception = pending.removeFirst()
            if (!seen.add(exception)) continue
            chain += exception
            exception.cause?.let(pending::addLast)
            pending.addAll(exception.suppressed)
        }
        return chain
    }

    /**
     * The usual `Class.method(File.kt:12)`, built here because `StackTraceElement.toString()` adds class loader and
     * module names on newer runtimes.
     */
    private fun StackTraceElement.describe(): String =
        "$className.$methodName(${fileName ?: "Unknown Source"}:$lineNumber)"

    companion object {

        /** The most frames kept per exception before the size cap starts cutting. */
        private const val MAX_FRAMES = 64

        /** The most exceptions looked at and described for one crash. */
        const val MAX_EXCEPTIONS = 16
    }
}
