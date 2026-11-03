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
     * The next events to send: counted events, then errors, then diagnostics, oldest first within each class, at most
     * [maxEvents] of them and [maxPayloadBytes] of payload. They stay stored until they are passed to [remove]. Empty
     * when nothing is stored, or when nothing can be read.
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
 * Each class has its own file and its own share of the disk, so a burst of one class can only evict that class. The
 * three shares, [COUNTED_BYTES] + [DIAGNOSTICS_BYTES] + [ERRORS_BYTES], are the 512 KiB of D-019. Diagnostics keep the
 * file name the single queue had before classes existed, so records written then are restored as diagnostics without
 * any change to the record format. Counted events have a new file, `counted`, which starts empty.
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

        /** The counted events' share of the disk. */
        const val COUNTED_BYTES = 192 * 1024

        /** The diagnostics' share of the disk. */
        const val DIAGNOSTICS_BYTES = 256 * 1024

        /** The errors' share of the disk. */
        const val ERRORS_BYTES = 64 * 1024

        /**
         * Counted events go first, then errors, so a batch cut short by its limits leaves diagnostics behind rather
         * than either of them.
         */
        private val SEND_ORDER = listOf(EventClass.COUNTED, EventClass.ERROR, EventClass.DIAGNOSTIC)

        /**
         * Opens the store in [directory], creating it if needed and restoring whatever an earlier process left. A file
         * written under a larger share of the disk is compacted to its current share.
         */
        @Throws(IOException::class)
        fun open(directory: File, clock: Clock = Clock.System): DiskEventStore {
            val dropped = DroppedEvents.open(File(directory, "dropped"))
            val queues = HashMap<EventClass, EventQueue>()
            try {
                for (eventClass in EventClass.entries) {
                    val limits = EventQueue.Limits(maxFileBytes = eventClass.maxFileBytes)
                    queues[eventClass] = EventQueue.open(File(directory, eventClass.fileName), dropped, limits, clock)
                }
            } catch (e: IOException) {
                queues.values.forEach { it.closeQuietly() }
                throw e
            }
            return DiskEventStore(queues, dropped)
        }

        private val EventClass.fileName: String
            get() = when (this) {
                EventClass.COUNTED -> "counted"
                EventClass.ERROR -> "errors"
                EventClass.DIAGNOSTIC -> "queue"
            }

        private val EventClass.maxFileBytes: Int
            get() = when (this) {
                EventClass.COUNTED -> COUNTED_BYTES
                EventClass.ERROR -> ERRORS_BYTES
                EventClass.DIAGNOSTIC -> DIAGNOSTICS_BYTES
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
