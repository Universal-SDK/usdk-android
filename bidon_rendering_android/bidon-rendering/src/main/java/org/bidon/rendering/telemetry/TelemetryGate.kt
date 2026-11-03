package org.bidon.rendering.telemetry

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The settings that decide which events are captured, as one value that is replaced whole (D-031). Until the server
 * supplies them (BID-92) every process runs on [DEFAULT].
 *
 * @property enabled the kill switch. Off: no new events and no sending; stored events stay until their TTL.
 * @property samplingRate the share of sessions whose events are kept, in [0, 1].
 * @property eventRates per event name, a rate in [0, 1] that replaces [samplingRate] for that name. Zero switches the
 * name off.
 * @property allowedEvents the only names that may be captured, errors and counted events included; `null` allows every
 * name.
 *
 * A rate outside (0, 1] switches off the diagnostic events it applies to; errors and counted events still pass.
 */
internal data class TelemetryConfig(
    val enabled: Boolean = true,
    val samplingRate: Double = 1.0,
    val eventRates: Map<String, Double> = emptyMap(),
    val allowedEvents: Set<String>? = null,
) {
    companion object {
        val DEFAULT = TelemetryConfig()
    }
}

/**
 * Where this session falls in [0, 1): the first 8 bytes of SHA-256 of the UTF-8 session id, read as an unsigned
 * big-endian number, whose top 53 bits are divided by 2^53. A session is kept at a rate when its position is below it,
 * so the server can repeat the decision from the session id alone.
 */
internal fun SessionId.samplingPosition(): Double {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    // 53 bits is a double's precision: every value converts exactly, so the result is the same on any platform and
    // stays below 1, which the full 64 bits rounded to a double would not.
    val top53 = ByteBuffer.wrap(digest, 0, 8).long ushr 11
    return top53.toDouble() / (1L shl 53).toDouble()
}

/**
 * Holds every event name to at most [maxPerWindow] events in any [windowMillis], measured on [clock]. Safe to call
 * from any thread. It keeps state per name for the life of the process, which stays small because names come from the
 * event catalogue.
 */
internal class RateLimiter(
    private val clock: MonotonicClock,
    private val maxPerWindow: Int = MAX_EVENTS_PER_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    /**
     * The times of the events admitted in the current window, oldest first, per name. Each holds at most
     * [maxPerWindow] entries: a refused attempt adds none.
     */
    private val admitted = HashMap<String, ArrayDeque<Long>>()

    /** Whether one more [eventName] fits now. A `true` uses up a place in the window. */
    fun tryAcquire(eventName: String): Boolean = synchronized(admitted) {
        // Read under the lock, so the times of each name are stored in order and the oldest is always first.
        val now = clock.elapsedMillis()
        val times = admitted.getOrPut(eventName) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= now - windowMillis) times.removeFirst()
        if (times.size >= maxPerWindow) return false
        times.addLast(now)
        true
    }

    companion object {
        /** At most this many events of one name in any [WINDOW_MILLIS] (D-031). */
        private const val MAX_EVENTS_PER_WINDOW = 20

        /** The window of the rate limit, one minute (D-031). */
        private const val WINDOW_MILLIS = 60_000L
    }
}

/**
 * Decides on the caller's thread whether an event is captured, and at which rate (D-031). The checks run in this
 * order: the kill switch, the allow-list, sampling, the rate limit. Errors and counted events are always kept at rate
 * 1.0 by sampling, and counted events skip the rate limit, but both still pass the kill switch and the allow-list.
 */
internal class TelemetryGate(
    sessionId: SessionId,
    private val limiter: RateLimiter,
    initial: TelemetryConfig? = TelemetryConfig.DEFAULT,
) {
    /**
     * The settings in force. A gate made with a `null` initial config is unsettled until [settle] or a set: reading
     * gives [TelemetryConfig.DEFAULT] meanwhile, and [BackgroundTelemetry] holds events instead of admitting them. A
     * set always wins over a later [settle]. In production it is replaced through [BackgroundTelemetry.applySettings],
     * which also wakes the telemetry thread.
     */
    var config: TelemetryConfig
        get() = state.get() ?: TelemetryConfig.DEFAULT
        set(value) = state.set(value)

    /** Null until settled. */
    private val state = AtomicReference(initial)

    /** Whether the gate has settings to decide with: a set has happened, or [settle] has. */
    val isSettled: Boolean get() = state.get() != null

    /**
     * Settles the gate with the settings an earlier process stored, [TelemetryConfig.DEFAULT] when there are none,
     * unless a set got there first.
     */
    fun settle(stored: TelemetryConfig?) {
        state.compareAndSet(null, stored ?: TelemetryConfig.DEFAULT)
    }

    private val position = sessionId.samplingPosition()

    private val rateLimited = AtomicLong()

    /** The rate to capture [eventName] with, or `null` when it must not be captured. */
    fun admit(eventName: String, eventClass: EventClass): SamplingRate? {
        // Read once, so a config replaced meanwhile cannot mix its settings with the old one's.
        val config = config
        if (!config.enabled) return null
        if (config.allowedEvents?.contains(eventName) == false) return null
        val rate = when (eventClass) {
            EventClass.ERROR, EventClass.COUNTED -> SamplingRate.ALL
            EventClass.DIAGNOSTIC ->
                SamplingRate.of(config.eventRates[eventName] ?: config.samplingRate)?.takeIf { position < it.fraction }
                    ?: return null
        }
        if (eventClass != EventClass.COUNTED && !limiter.tryAcquire(eventName)) {
            rateLimited.incrementAndGet()
            return null
        }
        return rate
    }

    /** How many events the rate limit has refused since the last call, which resets the count. */
    fun takeRateLimited(): Long = rateLimited.getAndSet(0)
}
