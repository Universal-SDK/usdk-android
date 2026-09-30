package org.bidon.rendering.telemetry

import java.io.File
import java.io.IOException

/**
 * Where the telemetry thread keeps events until they are sent. Confined to that thread.
 *
 * None of these calls is expected to throw: a store handles its own I/O failures. Anything that does escape is a
 * failure nobody anticipated, and switches telemetry off.
 */
internal interface EventStore {

    /** Stores [payload] for as long as its [eventClass] allows. A record that cannot be stored is counted by the store. */
    fun append(payload: ByteArray, eventClass: EventClass)

    /** Forces everything stored so far to the storage device. */
    fun sync()

    /** Counts [count] events lost for [reason], outside the store or after they left it. */
    fun countDropped(reason: DropReason, count: Long)

    /**
     * The next events to send: errors before diagnostics, oldest first within each class, at most [maxEvents] of them
     * and [maxPayloadBytes] of payload. They stay stored until they are passed to [remove]. Empty when nothing is
     * stored, or when nothing can be read.
     */
    fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent>

    /** Forgets [events], once the server has answered for them. */
    fun remove(events: List<StoredEvent>)

    /** How many events are stored, counting any that have expired since the store last looked. */
    val size: Int
}

/** An event read back from an [EventStore] by [EventStore.peek]: its payload exactly as it was stored. */
internal class StoredEvent(val eventClass: EventClass, internal val queued: EventQueue.QueuedEvent) {
    val payload: ByteArray get() = queued.payload
}

/**
 * The durable store: one [EventQueue] per [EventClass] and their shared [DroppedEvents], side by side in one directory.
 *
 * Each class has its own file and its own share of the disk, so a burst of diagnostics can only evict diagnostics.
 * Diagnostics keep the file name the single queue had before classes existed, so records written then are restored as
 * diagnostics without any change to the record format.
 */
internal class DiskEventStore private constructor(
    private val queues: Map<EventClass, EventQueue>,
    private val dropped: DroppedEvents,
) : EventStore {

    override fun append(payload: ByteArray, eventClass: EventClass) {
        queues.getValue(eventClass).append(payload, eventClass.ttlMillis)
    }

    override fun sync() {
        for (queue in queues.values) {
            try {
                queue.sync()
            } catch (_: IOException) {
                // Not retried. After a failed fsync the kernel may count the pages it could not write as clean, so a
                // second sync would report success without writing them. Records written before the failure can
                // therefore still be lost later, and the queue holds no other copy of them to write again.
            }
        }
    }

    override fun countDropped(reason: DropReason, count: Long) {
        dropped.add(reason, count)
    }

    override fun peek(maxEvents: Int, maxPayloadBytes: Int): List<StoredEvent> {
        val batch = ArrayList<StoredEvent>()
        var payloadBytes = 0
        for (eventClass in SEND_ORDER) {
            val queue = queues.getValue(eventClass)
            val events = maxEvents - batch.size
            val bytes = maxPayloadBytes - payloadBytes
            // A queue only hands out a batch that has room for its largest record; the next batch takes the rest.
            if (events <= 0 || bytes < queue.maxRecordBytes) break
            val peeked = try {
                queue.peek(events, bytes)
            } catch (_: IOException) {
                // Left in the file. A later peek may read it, and its TTL removes it if none can.
                emptyList()
            }
            for (event in peeked) {
                batch += StoredEvent(eventClass, event)
                payloadBytes += event.payload.size
            }
        }
        return batch
    }

    override fun remove(events: List<StoredEvent>) {
        for ((eventClass, ofClass) in events.groupBy { it.eventClass }) {
            queues.getValue(eventClass).remove(ofClass.map { it.queued })
        }
    }

    override val size: Int get() = queues.values.sumOf { it.size }

    companion object {

        /** The diagnostics' share of the disk. Together with [ERRORS_BYTES] it is the 512 KiB of D-019. */
        const val DIAGNOSTICS_BYTES = 448 * 1024

        /** The errors' share of the disk. */
        const val ERRORS_BYTES = 64 * 1024

        /** Errors go first, so a batch cut short by its limits leaves diagnostics behind rather than errors. */
        private val SEND_ORDER = listOf(EventClass.ERROR, EventClass.DIAGNOSTIC)

        /** Opens the store in [directory], creating it if needed and restoring whatever an earlier process left. */
        @Throws(IOException::class)
        fun open(directory: File, clock: Clock = Clock.System): DiskEventStore {
            val dropped = DroppedEvents.open(File(directory, "dropped"))
            val diagnostics = EventQueue.open(
                File(directory, "queue"),
                dropped,
                EventQueue.Limits(maxFileBytes = DIAGNOSTICS_BYTES),
                clock,
            )
            val errors = try {
                EventQueue.open(File(directory, "errors"), dropped, EventQueue.Limits(maxFileBytes = ERRORS_BYTES), clock)
            } catch (e: IOException) {
                diagnostics.closeQuietly()
                throw e
            }
            return DiskEventStore(mapOf(EventClass.DIAGNOSTIC to diagnostics, EventClass.ERROR to errors), dropped)
        }

        private fun EventQueue.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Nothing written through this handle is lost by a failed close.
            }
        }
    }
}
