package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Errors and diagnostics live in separate files with their own TTL and their own share of the disk (D-024), so a
 * diagnostic can never push an error out, and an error can wait longer than a diagnostic.
 */
class DiskEventStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val directory by lazy { temporary.newFolder() }
    private val clock = MutableClock(now = 1_800_000_000_000L)

    private fun open() = DiskEventStore.open(directory, clock)

    @Test
    fun `a flood of diagnostics evicts diagnostics only`() {
        val store = open()
        store.append("error".encodeToByteArray(), EventClass.ERROR)

        // Twice the whole budget, so the diagnostics file is evicted several times over.
        repeat(1_024) { store.append(ByteArray(1_024), EventClass.DIAGNOSTIC) }

        assertEquals(listOf("error"), restored(ERRORS_FILE))
        assertTrue(dropped().getValue(DropReason.EVICTED) > 0)
        assertTrue(File(directory, DIAGNOSTICS_FILE).length() <= DiskEventStore.DIAGNOSTICS_BYTES)
    }

    @Test
    fun `errors are evicted by newer errors, within their own share`() {
        val store = open()

        repeat(200) { store.append("e$it".padEnd(1_024).encodeToByteArray(), EventClass.ERROR) }

        val kept = restored(ERRORS_FILE)
        assertTrue(File(directory, ERRORS_FILE).length() <= DiskEventStore.ERRORS_BYTES)
        assertEquals("e199", kept.last().trim())
        assertTrue(kept.size < 200)
        assertEquals(0L, File(directory, DIAGNOSTICS_FILE).length() - HEADER_BYTES)
    }

    @Test
    fun `an error outlives the diagnostic TTL and expires after its own`() {
        open().apply {
            append("error".encodeToByteArray(), EventClass.ERROR)
            append("diagnostic".encodeToByteArray(), EventClass.DIAGNOSTIC)
        }

        clock.now += EventClass.DIAGNOSTIC.ttlMillis + 1
        open()
        assertEquals(listOf("error"), restored(ERRORS_FILE))
        assertEquals(emptyList<String>(), restored(DIAGNOSTICS_FILE))

        clock.now += EventClass.ERROR.ttlMillis
        open()
        assertEquals(emptyList<String>(), restored(ERRORS_FILE))
        assertEquals(2L, dropped().getValue(DropReason.EXPIRED))
    }

    @Test
    fun `the TTLs are 24 hours for errors and 15 minutes for diagnostics`() {
        assertEquals(TimeUnit.HOURS.toMillis(24), EventClass.ERROR.ttlMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(15), EventClass.DIAGNOSTIC.ttlMillis)
    }

    @Test
    fun `records written before event classes existed are restored as diagnostics`() {
        val dropped = DroppedEvents.open(File(directory, "dropped"))
        EventQueue.open(File(directory, DIAGNOSTICS_FILE), dropped, clock = clock).apply {
            append("old".encodeToByteArray(), TimeUnit.MINUTES.toMillis(15))
            close()
        }

        open()

        assertEquals(listOf("old"), restored(DIAGNOSTICS_FILE))
        assertEquals(emptyMap<DropReason, Long>(), dropped())
    }

    @Test
    fun `the two files together stay within the budget of the single queue they replace`() {
        assertEquals(512 * 1024, DiskEventStore.DIAGNOSTICS_BYTES + DiskEventStore.ERRORS_BYTES)
    }

    /** What a later process would restore from [name], read through a copy so the store's own files stay untouched. */
    private fun restored(name: String): List<String> {
        val copy = temporary.newFolder()
        File(directory, name).copyTo(File(copy, name))
        val queue = EventQueue.open(File(copy, name), DroppedEvents.open(File(copy, "dropped")), clock = clock)
        return queue.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload.decodeToString() }
    }

    private fun dropped(): Map<DropReason, Long> = DroppedEvents.open(File(directory, "dropped")).snapshot()

    private companion object {
        const val DIAGNOSTICS_FILE = "queue"
        const val ERRORS_FILE = "errors"
        const val HEADER_BYTES = 4L
    }
}
