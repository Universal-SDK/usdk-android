package org.bidon.rendering.telemetry

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where a crashing thread leaves its crash for the next launch: one file, `crash`, next to the event queues.
 *
 * The queues have one writer, the telemetry thread, and a crash can happen on any thread, at any moment, including
 * while that thread is busy or gone. So a crash does not go through the queues. The dying thread writes this file
 * itself, and the telemetry thread of the next process moves it into the error queue.
 *
 * The slot holds one crash. A later crash replaces an earlier one that has not been moved yet.
 */
internal class CrashSlot(private val directory: File) {

    private val slot = File(directory, "crash")
    private val claimed = File(directory, "crash.claimed")

    /**
     * Replaces the slot's content with [record]. Called on the crashing thread, so it blocks: the process is about to
     * die and nothing written later would survive it.
     *
     * One write into a temporary file, then a rename, so a reader never sees half a record. No fsync: process death
     * keeps what was written (D-019), and only a power loss in the next few seconds could lose it.
     *
     * Two threads can crash at the same moment, and both reach this before either lets the process die. They take
     * turns, so the temporary file never holds a mix of the two, and the slot keeps whichever wrote last.
     */
    @Throws(IOException::class)
    fun write(record: ByteArray) = synchronized(WRITE_LOCK) {
        directory.mkdirs()
        val temporary = File(directory, "crash.tmp")
        FileOutputStream(temporary).use { it.write(record) }
        if (!temporary.renameTo(slot)) throw IOException("could not rename $temporary to $slot")
    }

    /**
     * Moves the crash an earlier process left, if any, into [store] as an error. Called on the telemetry thread when
     * the store opens.
     *
     * The slot is claimed by renaming it first, so a crash this process writes meanwhile lands in a fresh slot instead
     * of being deleted with the old one. A claimed file left by a process that died while moving it is moved now; if
     * it had reached the store already, the event goes twice with the same `event_id`, and the server deduplicates it.
     */
    fun moveInto(store: EventStore) {
        if (claimed.exists()) moveClaimed(store)
        if (slot.renameTo(claimed)) moveClaimed(store)
    }

    private fun moveClaimed(store: EventStore) {
        val record = try {
            claimed.readBytes()
        } catch (_: IOException) {
            // Left in place for the next launch to try again.
            return
        }
        if (record.isNotEmpty()) store.append(record, EventClass.ERROR)
        claimed.delete()
    }

    private companion object {
        /** One for the process: every crash builds a slot of its own, and they all share one temporary file. */
        val WRITE_LOCK = Any()
    }
}
