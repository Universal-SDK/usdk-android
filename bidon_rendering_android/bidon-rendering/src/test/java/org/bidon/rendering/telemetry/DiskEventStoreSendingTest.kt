package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the store hands the sender: errors first, within the batch's limits, and gone only once removed. */
class DiskEventStoreSendingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val directory by lazy { temporary.newFolder() }
    private val store by lazy { DiskEventStore.open(directory, MutableClock(now = 1_800_000_000_000L)) }

    private fun List<StoredEvent>.names() = map { it.payload.decodeToString().trim() }

    @Test
    fun `errors come before diagnostics, oldest first within each class`() {
        store.append("d1".encodeToByteArray(), EventClass.DIAGNOSTIC)
        store.append("e1".encodeToByteArray(), EventClass.ERROR)
        store.append("d2".encodeToByteArray(), EventClass.DIAGNOSTIC)
        store.append("e2".encodeToByteArray(), EventClass.ERROR)

        val batch = store.peek(maxEvents = 10, maxPayloadBytes = LARGE_BUDGET)

        assertEquals(listOf("e1", "e2", "d1", "d2"), batch.names())
        assertEquals(
            listOf(EventClass.ERROR, EventClass.ERROR, EventClass.DIAGNOSTIC, EventClass.DIAGNOSTIC),
            batch.map { it.eventClass },
        )
    }

    @Test
    fun `a batch stops at the event limit, errors first`() {
        repeat(3) { store.append("e$it".encodeToByteArray(), EventClass.ERROR) }
        repeat(3) { store.append("d$it".encodeToByteArray(), EventClass.DIAGNOSTIC) }

        assertEquals(listOf("e0", "e1"), store.peek(maxEvents = 2, maxPayloadBytes = LARGE_BUDGET).names())
        assertEquals(listOf("e0", "e1", "e2", "d0"), store.peek(maxEvents = 4, maxPayloadBytes = LARGE_BUDGET).names())
    }

    @Test
    fun `a batch stops at the payload limit`() {
        repeat(4) { store.append("e$it".padEnd(10_000).encodeToByteArray(), EventClass.ERROR) }

        val batch = store.peek(maxEvents = 100, maxPayloadBytes = 25_000)

        assertEquals(listOf("e0", "e1"), batch.names())
        assertTrue(batch.sumOf { it.payload.size } <= 25_000)
    }

    @Test
    fun `diagnostics wait for the next batch when the errors leave less room than the largest record`() {
        store.append("e".padEnd(RECORD_CAP - 100).encodeToByteArray(), EventClass.ERROR)
        store.append("d".encodeToByteArray(), EventClass.DIAGNOSTIC)

        // After the error, the first leaves one byte less than a whole record, the second exactly one record.
        assertEquals(listOf("e"), store.peek(maxEvents = 100, maxPayloadBytes = 2 * RECORD_CAP - 101).names())
        assertEquals(listOf("e", "d"), store.peek(maxEvents = 100, maxPayloadBytes = 2 * RECORD_CAP - 100).names())
    }

    @Test
    fun `peeked events stay stored until they are removed, from both files`() {
        store.append("e".encodeToByteArray(), EventClass.ERROR)
        store.append("d1".encodeToByteArray(), EventClass.DIAGNOSTIC)
        store.append("d2".encodeToByteArray(), EventClass.DIAGNOSTIC)
        val batch = store.peek(maxEvents = 2, maxPayloadBytes = LARGE_BUDGET)

        assertEquals(batch.names(), store.peek(maxEvents = 2, maxPayloadBytes = LARGE_BUDGET).names())
        assertEquals(3, store.size)

        store.remove(batch)

        assertEquals(listOf("d2"), store.peek(maxEvents = 10, maxPayloadBytes = LARGE_BUDGET).names())
        assertEquals(1, store.size)
        val reopened = DiskEventStore.open(directory, MutableClock(now = 1_800_000_000_000L))
        assertEquals(listOf("d2"), reopened.peek(maxEvents = 10, maxPayloadBytes = LARGE_BUDGET).names())
    }

    @Test
    fun `an empty store gives an empty batch`() {
        assertTrue(store.peek(maxEvents = 100, maxPayloadBytes = LARGE_BUDGET).isEmpty())
        assertEquals(0, store.size)
    }

    @Test
    fun `a rejected count lands in the shared dropped file`() {
        store.countDropped(DropReason.REJECTED, 3)

        assertEquals(mapOf(DropReason.REJECTED to 3L), DroppedEvents.open(File(directory, "dropped")).snapshot())
    }

    private companion object {
        const val RECORD_CAP = 16 * 1024

        /** Room for everything these tests store, so only the limit a test is about can stop a batch. */
        const val LARGE_BUDGET = 1024 * 1024
    }
}
