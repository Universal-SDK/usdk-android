package org.bidon.rendering.telemetry

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Why a telemetry record was lost before it could be sent. [wireName] is the value reported to the server.
 */
internal enum class DropReason(val wireName: String) {
    /** The queue was full and the record was among the oldest. */
    EVICTED("evicted"),

    /** The record was handed over faster than the telemetry thread could store it, and never reached disk. */
    OVERFLOW("overflow"),

    /** The record outlived its TTL before it was sent. */
    EXPIRED("expired"),

    /** The record was larger than a single record may be, and was never stored. */
    OVERSIZE("oversize"),

    /** Stored data could not be read back. Counts one loss of at least one record, as the exact number is unknown. */
    CORRUPT("corrupt"),

    /** Writing the record failed. */
    IO_ERROR("io_error"),

    /** The server refused the record, or the batch it was sent in, with an answer that retrying cannot change. */
    REJECTED("rejected"),

    /** The record was not one JSON object, so no batch could carry it without making the whole batch invalid. */
    MALFORMED("malformed"),
}

/**
 * Counts of dropped telemetry records per [DropReason], persisted in their own small file.
 *
 * The counts deliberately live outside the queue they describe: a "records were dropped" record inside the queue
 * could itself be evicted, and writing it into a full queue would evict more. Here they are never evicted. They are
 * not sent yet: the ingest contract has no place for them (D-028).
 *
 * The sender reports a [snapshot] and, once the server has accepted it, [subtract]s exactly that snapshot. Drops
 * counted while the request was in flight stay counted.
 *
 * Not thread-safe; confined to the thread that owns the queue.
 */
internal class DroppedEvents private constructor(
    private val file: File,
    private val counts: LongArray,
) {

    fun add(reason: DropReason, count: Long) {
        if (count <= 0) return
        counts[reason.ordinal] += count
        persist()
    }

    /** The non-zero counts. */
    fun snapshot(): Map<DropReason, Long> =
        DropReason.entries.filter { counts[it.ordinal] > 0 }.associateWith { counts[it.ordinal] }

    fun subtract(reported: Map<DropReason, Long>) {
        for ((reason, count) in reported) {
            counts[reason.ordinal] = (counts[reason.ordinal] - count).coerceAtLeast(0)
        }
        persist()
    }

    /**
     * Writes the counts through a temporary file so a crash leaves either the old counts or the new ones. A failure
     * keeps the counts in memory, and the next change writes them again.
     */
    private fun persist() {
        val text = DropReason.entries.joinToString(separator = "") { "${it.wireName}=${counts[it.ordinal]}\n" }
        val temporary = File(file.path + ".tmp")
        try {
            file.parentFile?.mkdirs()
            RandomAccessFile(temporary, "rw").use { out ->
                out.setLength(0)
                out.write(text.toByteArray(Charsets.US_ASCII))
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) {
            temporary.delete()
        }
    }

    companion object {

        /**
         * Reads the counts from [file]. Lines that cannot be read are ignored, so a damaged file costs the counts it
         * held and nothing else.
         */
        fun open(file: File): DroppedEvents {
            // Left only by a process that died in the middle of a write, before the rename; the file itself is whole.
            File(file.path + ".tmp").delete()
            val counts = LongArray(DropReason.entries.size)
            val text = try {
                if (file.exists()) file.readText(Charsets.US_ASCII) else ""
            } catch (_: IOException) {
                ""
            }
            for (line in text.lineSequence()) {
                val reason = DropReason.entries.firstOrNull { line.startsWith(it.wireName + "=") } ?: continue
                val count = line.substringAfter('=').toLongOrNull() ?: continue
                if (count > 0) counts[reason.ordinal] = count
            }
            return DroppedEvents(file, counts)
        }
    }
}
