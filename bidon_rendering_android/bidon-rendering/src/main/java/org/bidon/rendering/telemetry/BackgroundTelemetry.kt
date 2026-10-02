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
 * Anything thrown on the telemetry thread switches telemetry off for the rest of the process: the thread ends, the
 * handoff is emptied, [emit] and [sync] do nothing, and [onFailure] hears about it once. A failure nobody anticipated
 * is not retried inside someone else's app.
 */
internal class BackgroundTelemetry private constructor(
    private val openStore: () -> EventStore,
    private val openSender: (EventStore) -> EventSender,
    private val onFailure: (Throwable) -> Unit,
    capacity: Int,
) : Telemetry {

    private val handoff = ArrayBlockingQueue<TelemetryEvent>(capacity)
    private val overflow = AtomicLong()

    /** A flag rather than a place in the handoff, so a full handoff cannot lose it. */
    private val syncRequested = AtomicBoolean()

    @Volatile
    private var running = true

    override fun emit(event: TelemetryEvent) {
        if (!running) return
        if (!handoff.offer(event)) overflow.incrementAndGet()
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
                sender.runIfDue()
                // Waits for the next event, or until the sender is due, whichever comes first.
                val wait = sender.millisUntilDue()
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

        /** Wakes the thread for a sync. Never stored. */
        private val WAKE_UP = TelemetryEvent { ByteArray(0) }

        /**
         * Java priority 4, which Android maps to its background thread priority: telemetry never competes with the
         * threads that draw an ad.
         */
        private const val THREAD_PRIORITY = Thread.NORM_PRIORITY - 1

        /**
         * Starts the telemetry thread and returns at once. [openStore] and [openSender] run on that thread, so the
         * disk work of opening and restoring the store never happens on the caller's.
         */
        fun start(
            openStore: () -> EventStore,
            onFailure: (Throwable) -> Unit,
            capacity: Int = HANDOFF_CAPACITY,
            openSender: (EventStore) -> EventSender = { EventSender.None },
        ): BackgroundTelemetry {
            val telemetry = BackgroundTelemetry(openStore, openSender, onFailure, capacity)
            Thread(telemetry::run, "bidon-telemetry").apply {
                isDaemon = true
                priority = THREAD_PRIORITY
                start()
            }
            return telemetry
        }
    }
}
