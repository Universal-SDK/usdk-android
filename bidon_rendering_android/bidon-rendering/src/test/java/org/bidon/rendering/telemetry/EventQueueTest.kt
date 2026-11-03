package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class EventQueueTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val clock = MutableClock(1_000_000L)
    private val directory by lazy { temporary.newFolder() }
    private val queueFile by lazy { File(directory, "queue") }
    private val dropped by lazy { DroppedEvents.open(File(directory, "dropped")) }

    private fun open(limits: EventQueue.Limits = EventQueue.Limits()) =
        EventQueue.open(queueFile, dropped, limits, clock)

    private fun EventQueue.all(): List<String> = peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload.decodeToString() }

    @Test
    fun `records come back in order after the queue is reopened`() {
        open().apply {
            append("a".encodeToByteArray(), TTL)
            append("b".encodeToByteArray(), TTL)
            append("".encodeToByteArray(), TTL)
            close()
        }

        assertEquals(listOf("a", "b", ""), open().all())
        assertTrue(dropped.snapshot().isEmpty())
    }

    /** Every launch opens the queue, so an intact one is read and left alone: a rewrite would cost every launch. */
    @Test
    fun `opening an intact queue leaves its file as it was`() {
        open().apply {
            repeat(100) { append("record $it".encodeToByteArray(), TTL) }
            close()
        }
        queueFile.setLastModified(LONG_AGO)
        val bytes = queueFile.readBytes()

        open().close()

        assertEquals(LONG_AGO, queueFile.lastModified())
        assertTrue(bytes.contentEquals(queueFile.readBytes()))
    }

    @Test
    fun `a record larger than the per-record cap is refused and counted`() {
        val queue = open(EventQueue.Limits(maxFileBytes = 4_096, maxRecordBytes = 100))

        assertTrue(queue.append(ByteArray(100), TTL))
        assertFalse(queue.append(ByteArray(101), TTL))

        assertEquals(1, queue.size)
        assertEquals(mapOf(DropReason.OVERSIZE to 1L), dropped.snapshot())
    }

    @Test
    fun `a record past its TTL is not handed out and is counted as expired`() {
        val queue = open()
        queue.append("short".encodeToByteArray(), ttlMillis = 1_000)
        queue.append("long".encodeToByteArray(), ttlMillis = 60_000)

        clock.now += 1_000

        assertEquals(listOf("long"), queue.all())
        assertEquals(mapOf(DropReason.EXPIRED to 1L), dropped.snapshot())
    }

    @Test
    fun `records restored after their TTL are dropped on open`() {
        open().apply {
            append("a".encodeToByteArray(), ttlMillis = 1_000)
            close()
        }
        clock.now += 5_000

        assertEquals(0, open().size)
        assertEquals(mapOf(DropReason.EXPIRED to 1L), dropped.snapshot())
    }

    @Test
    fun `a record dated further in the future than its TTL expires when the clock is set back`() {
        val queue = open()
        queue.append("a".encodeToByteArray(), ttlMillis = 1_000)

        clock.now -= 1_001

        assertEquals(emptyList<String>(), queue.all())
        assertEquals(mapOf(DropReason.EXPIRED to 1L), dropped.snapshot())
    }

    @Test
    fun `a small step back of the clock keeps the record`() {
        val queue = open()
        queue.append("a".encodeToByteArray(), ttlMillis = 1_000)

        clock.now -= 1_000

        assertEquals(listOf("a"), queue.all())
    }

    @Test
    fun `peek stops at the record count and at the payload budget`() {
        val queue = open(EventQueue.Limits(maxFileBytes = 4_096, maxRecordBytes = 100))
        repeat(5) { queue.append(ByteArray(40) { _ -> it.toByte() }, TTL) }

        assertEquals(3, queue.peek(maxRecords = 3, maxPayloadBytes = 1_000).size)
        assertEquals(2, queue.peek(maxRecords = 10, maxPayloadBytes = 100).size)
    }

    @Test
    fun `removed records are gone from the file, not only from memory`() {
        val queue = open()
        listOf("a", "b", "c").forEach { queue.append(it.encodeToByteArray(), TTL) }

        queue.remove(queue.peek(maxRecords = 2, maxPayloadBytes = Int.MAX_VALUE))
        queue.close()

        assertEquals(listOf("c"), open().all())
    }

    @Test
    fun `a full queue evicts the oldest records down to three quarters of the cap`() {
        val limits = EventQueue.Limits(maxFileBytes = 1_000, maxRecordBytes = 76)
        val queue = open(limits)
        // Each frame is 100 bytes: 24 of framing and 76 of payload. The header is 4, so nine fit.
        repeat(9) { queue.append(payload(it, 76), TTL) }
        assertEquals(9, queue.size)
        assertEquals(904L, queueFile.length())

        queue.append(payload(9, 76), TTL)

        // Down to 750 bytes before the new record: header plus 7 frames, then the new one.
        assertEquals((2..9).map { it.toString() }, queue.all().map { it.trimEnd('.') })
        assertEquals(804L, queueFile.length())
        assertEquals(mapOf(DropReason.EVICTED to 2L), dropped.snapshot())
    }

    @Test
    fun `eviction takes expired records before live ones`() {
        val limits = EventQueue.Limits(maxFileBytes = 1_000, maxRecordBytes = 76)
        val queue = open(limits)
        queue.append(payload(0, 76), ttlMillis = 60_000)
        queue.append(payload(1, 76), ttlMillis = 10)
        repeat(7) { queue.append(payload(it + 2, 76), ttlMillis = 60_000) }
        clock.now += 10

        queue.append(payload(9, 76), ttlMillis = 60_000)

        assertEquals(listOf("2", "3", "4", "5", "6", "7", "8", "9"), queue.all().map { it.trimEnd('.') })
        assertEquals(mapOf(DropReason.EVICTED to 1L, DropReason.EXPIRED to 1L), dropped.snapshot())
    }

    @Test
    fun `removing a batch after part of it was evicted removes only what is still there`() {
        val limits = EventQueue.Limits(maxFileBytes = 1_000, maxRecordBytes = 76)
        val queue = open(limits)
        repeat(9) { queue.append(payload(it, 76), TTL) }
        val inFlight = queue.peek(maxRecords = 4, maxPayloadBytes = Int.MAX_VALUE)

        queue.append(payload(9, 76), TTL) // evicts 0 and 1 while 0..3 are being sent
        queue.remove(inFlight)

        assertEquals((4..9).map { it.toString() }, queue.all().map { it.trimEnd('.') })
        assertEquals(mapOf(DropReason.EVICTED to 2L), dropped.snapshot())
    }

    @Test
    fun `a torn tail is dropped, the file is cut at the last whole record, and the next record survives`() {
        open().apply {
            append("first".encodeToByteArray(), TTL)
            append("second".encodeToByteArray(), TTL)
            close()
        }
        val whole = queueFile.length()
        RandomAccessFile(queueFile, "rw").use { it.setLength(whole - 3) }
        val firstRecordEnd = 4L + 24 + "first".length

        open().apply {
            assertEquals(firstRecordEnd, queueFile.length())
            append("third".encodeToByteArray(), TTL)
            close()
        }

        assertEquals(listOf("first", "third"), open().all())
        assertEquals(mapOf(DropReason.CORRUPT to 1L), dropped.snapshot())
    }

    @Test
    fun `a damaged record in the middle drops it and everything after it, counted once`() {
        open().apply {
            listOf("a", "b", "c").forEach { append(it.encodeToByteArray(), TTL) }
            close()
        }
        val secondPayloadAt = 4L + (24 + 1) + 20
        RandomAccessFile(queueFile, "rw").use {
            it.seek(secondPayloadAt)
            it.write('x'.code)
        }

        assertEquals(listOf("a"), open().all())
        assertEquals(mapOf(DropReason.CORRUPT to 1L), dropped.snapshot())
    }

    @Test
    fun `an unrecognised file is reset and counted once`() {
        queueFile.writeText("not a queue")

        val queue = open()

        assertEquals(0, queue.size)
        assertEquals(4L, queueFile.length())
        assertEquals(mapOf(DropReason.CORRUPT to 1L), dropped.snapshot())
    }

    @Test
    fun `a missing file is a new empty queue, not a loss`() {
        assertEquals(0, open().size)
        assertTrue(dropped.snapshot().isEmpty())
    }

    @Test
    fun `a file larger than a lowered cap is compacted on open`() {
        open().apply {
            repeat(10) { append(payload(it, 76), TTL) }
            close()
        }

        val queue = open(EventQueue.Limits(maxFileBytes = 500, maxRecordBytes = 76))

        assertTrue(queueFile.length() <= 500)
        assertEquals(listOf("7", "8", "9"), queue.all().map { it.trimEnd('.') })
        assertEquals(mapOf(DropReason.EVICTED to 7L), dropped.snapshot())
    }

    @Test
    fun `an expired record is counted once, however many times the app is relaunched`() {
        open().apply {
            append("a".encodeToByteArray(), ttlMillis = 1_000)
            append("b".encodeToByteArray(), TTL)
            close()
        }
        clock.now += 5_000

        repeat(3) { open().close() }

        assertEquals(listOf("b"), open().all())
        assertEquals(mapOf(DropReason.EXPIRED to 1L), dropped.snapshot())
    }

    @Test
    fun `when the file cannot be rewritten, a record that needs room is refused and counted, and nothing is lost`() {
        val queue = open(EventQueue.Limits(maxFileBytes = 1_000, maxRecordBytes = 76))
        repeat(9) { queue.append(payload(it, 76), TTL) }
        blockRewrites()

        assertFalse(queue.append(payload(9, 76), TTL))

        assertEquals((0..8).map { it.toString() }, queue.all().map { it.trimEnd('.') })
        assertEquals(mapOf(DropReason.IO_ERROR to 1L), dropped.snapshot())
    }

    @Test
    fun `when the file cannot be rewritten, removed records come back on the next launch rather than being lost`() {
        val queue = open()
        listOf("a", "b", "c").forEach { queue.append(it.encodeToByteArray(), TTL) }
        blockRewrites()

        queue.remove(queue.peek(maxRecords = 2, maxPayloadBytes = Int.MAX_VALUE))
        assertEquals(listOf("c"), queue.all())
        queue.close()

        assertEquals(listOf("a", "b", "c"), open().all())
        assertTrue(dropped.snapshot().isEmpty())
    }

    @Test
    fun `at the smallest legal limits a full queue still takes the largest record`() {
        val limits = EventQueue.Limits(maxFileBytes = 4 + 24 + 1_000, maxRecordBytes = 1_000)
        val queue = open(limits)
        queue.append(ByteArray(1_000) { 1 }, TTL)

        assertTrue(queue.append(ByteArray(1_000) { 2 }, TTL))

        assertEquals(listOf(2.toByte()), queue.peek(10, 1_000).map { it.payload.distinct().single() })
        assertEquals(1_028L, queueFile.length())
        assertEquals(mapOf(DropReason.EVICTED to 1L), dropped.snapshot())
    }

    @Test
    fun `a record restored from under a larger per-record cap is dropped, not left blocking the queue`() {
        open().apply {
            append(ByteArray(500), TTL)
            append("small".encodeToByteArray(), TTL)
            close()
        }

        val queue = open(EventQueue.Limits(maxFileBytes = 4_096, maxRecordBytes = 100))

        val restored = queue.peek(maxRecords = 10, maxPayloadBytes = 100).map { it.payload.decodeToString() }
        assertEquals(listOf("small"), restored)
        assertEquals(mapOf(DropReason.OVERSIZE to 1L), dropped.snapshot())
    }

    @Test
    fun `a rewrite interrupted before its rename leaves the queue whole and its temporary file is cleaned up`() {
        open().apply {
            append("a".encodeToByteArray(), TTL)
            close()
        }
        val leftover = File(directory, "queue.tmp").apply { writeBytes(ByteArray(300)) }

        assertEquals(listOf("a"), open().all())
        assertFalse(leftover.exists())
        assertTrue(dropped.snapshot().isEmpty())
    }

    @Test
    fun `a file too large to be ours is reset rather than read into memory`() {
        RandomAccessFile(queueFile, "rw").use {
            it.write(byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'Q'.code.toByte(), 1))
            it.setLength(8L * 1024 * 1024 + 1)
        }

        assertEquals(0, open().size)
        assertEquals(4L, queueFile.length())
        assertEquals(mapOf(DropReason.CORRUPT to 1L), dropped.snapshot())
    }

    @Test
    fun `synced records read back as written`() {
        open().apply {
            append("a".encodeToByteArray(), TTL)
            sync()
            close()
        }

        assertEquals(listOf("a"), open().all())
    }

    @Test
    fun `limits that cannot hold a record, or exceed what the queue will read back, are rejected`() {
        val rejected = IllegalArgumentException::class.java
        assertThrows(rejected) { EventQueue.Limits(maxFileBytes = 1_027, maxRecordBytes = 1_000) }
        assertThrows(rejected) { EventQueue.Limits(maxRecordBytes = 256 * 1024 + 1) }
        assertThrows(rejected) { EventQueue.Limits(maxFileBytes = 8 * 1024 * 1024 + 1) }
    }

    /**
     * Makes every rewrite of the file fail from now on: the temporary file it writes through is a directory that
     * cannot be deleted.
     */
    private fun blockRewrites() {
        File(directory, "queue.tmp").apply { mkdir() }.resolve("occupied").writeText("")
    }

    /** [index] padded with dots to exactly [size] bytes. */
    private fun payload(index: Int, size: Int): ByteArray = index.toString().padEnd(size, '.').encodeToByteArray()

    private companion object {
        const val TTL = 15 * 60 * 1_000L

        /** A whole second, which every file system keeps exactly, and far enough back that no write lands on it. */
        const val LONG_AGO = 1_000_000_000_000L
    }
}

internal class MutableClock(var now: Long) : Clock {
    override fun currentTimeMillis(): Long = now
}
