package org.bidon.rendering.telemetry

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [Telemetry] with a thread of its own, `bidon-telemetry`, which owns the [EventStore] and its [EventSender].
 *
 * The caller's share of [emit] is one non-blocking offer into a bounded handoff. Everything else happens on the
 * telemetry thread: opening the store (which restores it from disk), encoding events, writing them, syncing and
 * sending. When the handoff is full the event is dropped and counted as [DropReason.OVERFLOW]. The thread has fallen
 * behind, for example behind a slow request, and making the caller wait instead would put telemetry on the ad path.
 *
 * Every event passes the [TelemetryGate] on the caller's thread before it is captured (D-031). Events the rate limit
 * refuses are counted as [DropReason.RATE_LIMITED]. While the gate's kill switch is off the sender is not run; events
 * already stored stay, and a sync still reaches the store. Switching off does not interrupt a send pass already
 * running.
 *
 * Anything thrown on the telemetry thread switches telemetry off for the rest of the process: the thread ends, the
 * handoff is emptied, [emit] and [sync] do nothing, and [onFailure] hears about it once. A failure nobody anticipated
 * is not retried inside someone else's app.
 */
internal class BackgroundTelemetry private constructor(
    private val openStore: () -> EventStore,
    private val openSender: (EventStore) -> EventSender,
    private val onFailure: (Throwable) -> Unit,
    private val gate: TelemetryGate,
    capacity: Int,
) : Telemetry {

    private val handoff = ArrayBlockingQueue<TelemetryEvent>(capacity)
    private val overflow = AtomicLong()

    /** A flag rather than a place in the handoff, so a full handoff cannot lose it. */
    private val syncRequested = AtomicBoolean()

    @Volatile
    private var running = true

    override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) {
        if (!running) return
        val rate = gate.admit(eventName, eventClass) ?: return
        val event = try {
            capture(rate)
        } catch (failure: Throwable) {
            // A broken capture loses only its own event; the exception must not reach the ad. An interrupt is the
            // caller's to act on, so its flag is set again.
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return
        }
        if (!handoff.offer(event)) overflow.incrementAndGet()
    }

    /** Replaces the settings that decide which events are captured, and wakes the thread so it acts on them at once. */
    fun configure(config: TelemetryConfig) {
        gate.config = config
        if (!running) return
        // If the handoff is full the thread is busy and reads the new config before it next waits.
        handoff.offer(WAKE_UP)
    }

    override fun sync() {
        if (!running) return
        syncRequested.set(true)
        // Wakes the thread if it is waiting. If the handoff is full the thread is busy and sees the flag anyway.
        handoff.offer(WAKE_UP)
    }

    private fun run() {
        try {
            val store = openStore()
            val sender = openSender(store)
            sender.afterStart()
            while (true) {
                val sending = gate.config.enabled
                if (sending) sender.runIfDue()
                // Waits for the next event, or until the sender is due, whichever comes first. Switched off, it waits
                // only for an event, a sync or [configure].
                val wait = if (sending) sender.millisUntilDue() else null
                val event = if (wait == null) handoff.take() else handoff.poll(wait, TimeUnit.MILLISECONDS)
                if (event != null && event !== WAKE_UP) {
                    store.append(event.encode(), event.eventClass)
                    sender.afterAppend()
                }
                val syncing = syncRequested.getAndSet(false)
                // Overflow means the thread is behind. Recording it after every event would add a durable write to
                // each one exactly when there is no time for it, so it waits until the backlog is gone, or for a sync.
                if (syncing || handoff.isEmpty()) {
                    val lost = overflow.getAndSet(0)
                    if (lost > 0) store.countDropped(DropReason.OVERFLOW, lost)
                    val limited = gate.takeRateLimited()
                    if (limited > 0) store.countDropped(DropReason.RATE_LIMITED, limited)
                }
                if (syncing) {
                    store.sync()
                    sender.afterSync()
                }
            }
        } catch (failure: Throwable) {
            running = false
            // An emit that checked running just before this may still add an event; it stays in memory, unwritten.
            handoff.clear()
            try {
                onFailure(failure)
            } catch (_: Throwable) {
                // Reporting is best effort. Nothing may escape to the thread's uncaught exception handler.
            }
        }
    }

    companion object {

        /** How many events can wait for the telemetry thread before new ones are dropped. */
        const val HANDOFF_CAPACITY = 256

        /** Wakes the thread for a sync or a new config. Never stored. */
        private val WAKE_UP = TelemetryEvent { ByteArray(0) }

        /**
         * Java priority 4, which Android maps to its background thread priority: telemetry never competes with the
         * threads that draw an ad.
         */
        private const val THREAD_PRIORITY = Thread.NORM_PRIORITY - 1

        /**
         * Starts the telemetry thread and returns at once. [openStore] and [openSender] run on that thread, so the
         * disk work of opening and restoring the store never happens on the caller's. [gate] has no default: its
         * session id must be the one the events and batches carry, or sampling decides on a session nobody sees.
         */
        fun start(
            openStore: () -> EventStore,
            onFailure: (Throwable) -> Unit,
            capacity: Int = HANDOFF_CAPACITY,
            openSender: (EventStore) -> EventSender = { EventSender.None },
            gate: TelemetryGate,
        ): BackgroundTelemetry {
            val telemetry = BackgroundTelemetry(openStore, openSender, onFailure, gate, capacity)
            Thread(telemetry::run, "bidon-telemetry").apply {
                isDaemon = true
                priority = THREAD_PRIORITY
                start()
            }
            return telemetry
        }
    }
}
