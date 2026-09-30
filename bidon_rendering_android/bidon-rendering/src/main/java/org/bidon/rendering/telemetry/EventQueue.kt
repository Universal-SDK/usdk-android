package org.bidon.rendering.telemetry

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A bounded queue of opaque telemetry records, kept in one file so that it survives the death of the process.
 *
 * The queue stores bytes and never looks inside them. Each record is framed as
 * `[u32 length][i64 enqueued at][i64 ttl][payload][u32 crc32]` behind a four-byte file header, so the format places no
 * constraint on the payload, and the checksum tells a real record from the garbage a power loss can leave behind.
 *
 * Durability. An appended record is handed to the kernel in a single unbuffered write before [append] returns. That
 * survives the process being killed, because the kernel still holds the data. It does not survive a kernel crash or a
 * power loss until the file is synced; [sync] does that, and the owner calls it when the app goes to the background.
 *
 * Restore. Opening the queue reads the file once and returns every record up to the first one that is incomplete or
 * damaged. The file is truncated there, so a torn tail left by an interrupted write is never followed by a new record.
 *
 * Limits. The file never grows past [Limits.maxFileBytes]. When a record would not fit, expired records and then the
 * oldest ones are evicted until the queue is at three quarters of the cap, so a full queue is not rewritten on every
 * append. Every record carries its own TTL. Everything the queue loses is counted in [dropped], once, when it leaves
 * the file.
 *
 * Not thread-safe. The owner confines every call, including [open], to one thread.
 */
internal class EventQueue private constructor(
    private val file: File,
    private val limits: Limits,
    private val clock: Clock,
    private val dropped: DroppedEvents,
    private var storage: RandomAccessFile,
    private val entries: ArrayDeque<Entry>,
) {

    /**
     * @property maxFileBytes the most the queue file may hold, header included.
     * @property maxRecordBytes the largest payload a single record may carry; larger ones are refused.
     */
    class Limits(
        val maxFileBytes: Int = 512 * 1024,
        val maxRecordBytes: Int = 16 * 1024,
    ) {
        init {
            require(maxRecordBytes in 0..MAX_PAYLOAD_BYTES) { "maxRecordBytes must be in 0..$MAX_PAYLOAD_BYTES" }
            require(maxFileBytes in HEADER.size + FRAME_OVERHEAD + maxRecordBytes..MAX_FILE_BYTES) {
                "maxFileBytes must hold the largest record and be at most $MAX_FILE_BYTES"
            }
        }

        /**
         * The size eviction brings the file down to before a record of [frameSize] bytes is written: three quarters
         * of the cap, or less when the record would not otherwise fit.
         */
        internal fun evictionBudget(frameSize: Int): Long =
            minOf(maxFileBytes / 4 * 3, maxFileBytes - frameSize).toLong()
    }

    /** A record handed out by [peek], to be passed back to [remove] once the server has accepted it. */
    class QueuedEvent internal constructor(internal val sequence: Long, val payload: ByteArray)

    /** Where a record sits in the file. [sequence] identifies it in memory only and is not persisted. */
    private class Entry(
        val sequence: Long,
        val offset: Long,
        val frameSize: Int,
        val enqueuedAt: Long,
        val ttlMillis: Long,
    ) {
        val payloadSize: Int get() = frameSize - FRAME_OVERHEAD

        /** Expired once its TTL has passed, or when it is dated further in the future than its TTL. */
        fun isExpired(now: Long): Boolean = now - enqueuedAt >= ttlMillis || enqueuedAt - now > ttlMillis
    }

    /**
     * Larger than a record may be under the current limits. Possible only for a record restored from a file written
     * under larger ones.
     */
    private fun Entry.isOversize(): Boolean = payloadSize > limits.maxRecordBytes

    private var fileLength: Long = storage.length()
    private var nextSequence: Long = (entries.lastOrNull()?.sequence ?: -1) + 1

    /** The number of records held, including any that have expired since the file was last rewritten. */
    val size: Int get() = entries.size

    /** The largest payload a record may carry under the current limits. */
    val maxRecordBytes: Int get() = limits.maxRecordBytes

    /**
     * Stores [payload] until it is removed, evicted, or older than [ttlMillis].
     *
     * @return false when the record was not stored; the reason has been counted.
     */
    fun append(payload: ByteArray, ttlMillis: Long): Boolean {
        require(ttlMillis > 0) { "ttlMillis must be positive" }
        if (payload.size > limits.maxRecordBytes) {
            dropped.add(DropReason.OVERSIZE, 1)
            return false
        }
        val now = clock.currentTimeMillis()
        val frame = encode(payload, now, ttlMillis)
        if (fileLength + frame.size > limits.maxFileBytes) {
            try {
                compact(budget = limits.evictionBudget(frame.size))
            } catch (_: IOException) {
                dropped.add(DropReason.IO_ERROR, 1)
                return false
            }
        }
        val offset = fileLength
        return try {
            storage.seek(offset)
            storage.write(frame)
            entries.addLast(Entry(nextSequence++, offset, frame.size, now, ttlMillis))
            fileLength = offset + frame.size
            true
        } catch (_: IOException) {
            dropped.add(DropReason.IO_ERROR, 1)
            // A partial write would sit in front of every later record; cut it off if the file still allows it.
            try {
                storage.setLength(offset)
            } catch (_: IOException) {
                // The next append overwrites it, as the length it writes at has not moved. Whatever a shorter record
                // leaves behind is cut off by the next open and counted there as corrupt.
            }
            false
        }
    }

    /**
     * The oldest live records, in order, up to [maxRecords] and [maxPayloadBytes] of payload. Records that have expired
     * are removed from the file first, and counted. If that rewrite fails they are skipped instead, and counted by the
     * next rewrite that succeeds.
     */
    @Throws(IOException::class)
    fun peek(maxRecords: Int, maxPayloadBytes: Int): List<QueuedEvent> {
        require(maxRecords > 0) { "maxRecords must be positive" }
        require(maxPayloadBytes >= limits.maxRecordBytes) { "maxPayloadBytes must fit the largest record" }
        purge()
        val now = clock.currentTimeMillis()
        val batch = ArrayList<QueuedEvent>()
        var payloadBytes = 0L
        for (entry in entries) {
            // Present only when the rewrite in purge() failed; the next one that succeeds counts them.
            if (entry.isExpired(now) || entry.isOversize()) continue
            if (batch.size == maxRecords || payloadBytes + entry.payloadSize > maxPayloadBytes) break
            val payload = ByteArray(entry.payloadSize)
            storage.seek(entry.offset + FRAME_HEADER_SIZE)
            storage.readFully(payload)
            batch += QueuedEvent(entry.sequence, payload)
            payloadBytes += entry.payloadSize
        }
        return batch
    }

    /**
     * Forgets [events] and rewrites the file without them. Records evicted since they were peeked are simply no longer
     * there. If the rewrite fails, the records are forgotten for this process but remain on disk, and the next launch
     * sends them again: a duplicate rather than a loss.
     */
    fun remove(events: Collection<QueuedEvent>) {
        if (events.isEmpty()) return
        val sequences = events.mapTo(HashSet()) { it.sequence }
        if (!entries.removeAll { it.sequence in sequences }) return
        try {
            compact(budget = Long.MAX_VALUE)
        } catch (_: IOException) {
            // Kept as described above.
        }
    }

    /** Forces every record written so far to the storage device. */
    @Throws(IOException::class)
    fun sync() {
        storage.fd.sync()
    }

    @Throws(IOException::class)
    fun close() {
        storage.close()
    }

    /**
     * Rewrites the file if it holds anything that has to go: records past their TTL or over the record cap, or more
     * than the file cap. A failed rewrite leaves everything where it was, to be counted by the next one that succeeds.
     */
    private fun purge() {
        val now = clock.currentTimeMillis()
        val overCap = fileLength > limits.maxFileBytes
        if (!overCap && entries.none { it.isExpired(now) || it.isOversize() }) return
        try {
            compact(budget = if (overCap) limits.evictionBudget(frameSize = 0) else Long.MAX_VALUE)
        } catch (_: IOException) {
            // Kept as described above.
        }
    }

    /**
     * Rewrites the file with the live records, dropping expired and oversize ones and then the oldest until the file
     * fits in [budget] bytes. The new file is synced before it replaces the old one, so a crash at any point leaves
     * one of the two whole. On failure nothing has changed, in memory or on disk.
     *
     * The handle that wrote the new file becomes the queue's handle, so nothing after the rename can fail except
     * closing the old one, which loses nothing. What was dropped is counted once the new file is in place. A process
     * that dies between the two has lost those records without counting them; counting first would report losses
     * that a failed rename then did not cause.
     */
    @Throws(IOException::class)
    private fun compact(budget: Long) {
        val now = clock.currentTimeMillis()
        val live = ArrayList<Entry>(entries.size)
        var expired = 0
        var oversize = 0
        for (entry in entries) {
            when {
                entry.isExpired(now) -> expired++
                entry.isOversize() -> oversize++
                else -> live += entry
            }
        }
        var keptBytes = HEADER.size.toLong() + live.sumOf { it.frameSize.toLong() }
        var evicted = 0
        while (keptBytes > budget && evicted < live.size) {
            keptBytes -= live[evicted].frameSize
            evicted++
        }
        val kept = live.subList(evicted, live.size)

        // One read of the old file and one write of the new one: a full queue holds thousands of small records.
        val current = ByteArray(fileLength.toInt())
        storage.seek(0)
        storage.readFully(current)
        val replacement = ByteArray(keptBytes.toInt())
        HEADER.copyInto(replacement)
        val rewritten = ArrayList<Entry>(kept.size)
        var offset = HEADER.size
        for (entry in kept) {
            current.copyInto(replacement, offset, entry.offset.toInt(), entry.offset.toInt() + entry.frameSize)
            rewritten += Entry(entry.sequence, offset.toLong(), entry.frameSize, entry.enqueuedAt, entry.ttlMillis)
            offset += entry.frameSize
        }

        val temporary = temporaryFile(file)
        val replacing = RandomAccessFile(temporary, "rw")
        try {
            replacing.setLength(0)
            replacing.write(replacement)
            replacing.fd.sync()
            if (!temporary.renameTo(file)) throw IOException("Could not replace $file")
        } catch (e: IOException) {
            replacing.closeQuietly()
            temporary.delete()
            throw e
        }

        val previous = storage
        storage = replacing
        entries.clear()
        entries.addAll(rewritten)
        fileLength = replacement.size.toLong()
        previous.closeQuietly()
        dropped.add(DropReason.EXPIRED, expired.toLong())
        dropped.add(DropReason.OVERSIZE, oversize.toLong())
        dropped.add(DropReason.EVICTED, evicted.toLong())
    }

    companion object {

        private val HEADER = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'Q'.code.toByte(), 1)

        /** Length, enqueue time and TTL. */
        private const val FRAME_HEADER_SIZE = 4 + 8 + 8
        private const val FRAME_OVERHEAD = FRAME_HEADER_SIZE + 4

        /** A bound on what a length field may claim, so a damaged one is recognised before anything is allocated. */
        private const val MAX_PAYLOAD_BYTES = 256 * 1024

        /** A bound on the file itself, so a file that is not ours is never read into memory whole. */
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024

        /**
         * Opens the queue in [file], creating it if needed, and restores every intact record. A damaged tail is cut off
         * and counted. Records that expired while the app was not running, and anything over the current limits, are
         * removed and counted.
         */
        @Throws(IOException::class)
        fun open(
            file: File,
            dropped: DroppedEvents,
            limits: Limits = Limits(),
            clock: Clock = Clock.System,
        ): EventQueue {
            // Left only by a process that died in the middle of a rewrite, before the rename; the file itself is whole.
            temporaryFile(file).delete()
            file.parentFile?.mkdirs()
            val storage = RandomAccessFile(file, "rw")
            try {
                val queue = EventQueue(file, limits, clock, dropped, storage, restore(storage, dropped))
                queue.purge()
                return queue
            } catch (e: Throwable) {
                storage.closeQuietly()
                throw e
            }
        }

        private fun temporaryFile(file: File) = File(file.path + ".tmp")

        private fun RandomAccessFile.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Nothing written through this handle is lost by a failed close.
            }
        }

        /**
         * Reads the whole file once and indexes every record up to the first one that is incomplete or fails its
         * checksum, then truncates the file there. An unrecognised or implausibly large file is reset. The loss is
         * counted once the file has been repaired, so a failed repair is not counted again by the next attempt.
         */
        private fun restore(storage: RandomAccessFile, dropped: DroppedEvents): ArrayDeque<Entry> {
            val entries = ArrayDeque<Entry>()
            val length = storage.length()
            val bytes = if (length <= MAX_FILE_BYTES) ByteArray(length.toInt()) else null
            if (bytes != null) {
                storage.seek(0)
                storage.readFully(bytes)
            }
            if (bytes == null || bytes.size < HEADER.size || !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
                storage.setLength(0)
                storage.write(HEADER)
                if (length > 0) dropped.add(DropReason.CORRUPT, 1)
                return entries
            }

            val buffer = ByteBuffer.wrap(bytes)
            var offset = HEADER.size
            while (offset < bytes.size) {
                val frameSize = validFrameSize(bytes, buffer, offset) ?: break
                buffer.position(offset + 4)
                val enqueuedAt = buffer.getLong()
                val ttlMillis = buffer.getLong()
                entries.addLast(Entry(entries.size.toLong(), offset.toLong(), frameSize, enqueuedAt, ttlMillis))
                offset += frameSize
            }
            if (offset < bytes.size) {
                storage.setLength(offset.toLong())
                dropped.add(DropReason.CORRUPT, 1)
            }
            return entries
        }

        /** The size of the frame starting at [offset], or null when it is incomplete or damaged. */
        private fun validFrameSize(bytes: ByteArray, buffer: ByteBuffer, offset: Int): Int? {
            if (bytes.size - offset < FRAME_OVERHEAD) return null
            val payloadSize = buffer.getInt(offset)
            if (payloadSize !in 0..MAX_PAYLOAD_BYTES) return null
            val frameSize = FRAME_OVERHEAD + payloadSize
            if (bytes.size - offset < frameSize) return null
            val checksumAt = offset + frameSize - 4
            val crc = CRC32().apply { update(bytes, offset, checksumAt - offset) }
            return if (crc.value.toInt() == buffer.getInt(checksumAt)) frameSize else null
        }

        private fun encode(payload: ByteArray, enqueuedAt: Long, ttlMillis: Long): ByteArray {
            val frame = ByteBuffer.allocate(FRAME_OVERHEAD + payload.size)
            frame.putInt(payload.size).putLong(enqueuedAt).putLong(ttlMillis).put(payload)
            val crc = CRC32().apply { update(frame.array(), 0, frame.position()) }
            frame.putInt(crc.value.toInt())
            return frame.array()
        }
    }
}
