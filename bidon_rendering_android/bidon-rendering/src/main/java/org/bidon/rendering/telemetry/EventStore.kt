package org.bidon.rendering.telemetry

import java.io.File
import java.io.IOException

/**
 * Where the telemetry thread keeps events. Confined to that thread.
 *
 * None of these calls is expected to throw: a store handles its own I/O failures. Anything that does escape is a
 * failure nobody anticipated, and switches telemetry off.
 */
internal interface EventStore {

    /** Stores [payload] for [ttlMillis]. A record that cannot be stored is counted by the store. */
    fun append(payload: ByteArray, ttlMillis: Long)

    /** Forces everything stored so far to the storage device. */
    fun sync()

    /** Counts [count] events lost for [reason] before they reached the store. */
    fun countDropped(reason: DropReason, count: Long)
}

/** The durable store: an [EventQueue] and its [DroppedEvents], side by side in one directory. */
internal class DiskEventStore private constructor(
    private val queue: EventQueue,
    private val dropped: DroppedEvents,
) : EventStore {

    override fun append(payload: ByteArray, ttlMillis: Long) {
        queue.append(payload, ttlMillis)
    }

    override fun sync() {
        try {
            queue.sync()
        } catch (_: IOException) {
            // The records are still with the kernel, which is what survives the process being killed. The next sync
            // tries again.
        }
    }

    override fun countDropped(reason: DropReason, count: Long) {
        dropped.add(reason, count)
    }

    companion object {

        /** Opens the store in [directory], creating it if needed and restoring whatever an earlier process left. */
        @Throws(IOException::class)
        fun open(directory: File): DiskEventStore {
            val dropped = DroppedEvents.open(File(directory, "dropped"))
            return DiskEventStore(EventQueue.open(File(directory, "queue"), dropped), dropped)
        }
    }
}
