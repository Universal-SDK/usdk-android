package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The queue's promises, checked against an independent model over arbitrary sequences of operations, and against
 * arbitrary damage to the file.
 */
class EventQueuePropertyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private sealed interface Operation {
        data class Append(val size: Int, val shortLived: Boolean) : Operation
        data class Acknowledge(val count: Int) : Operation
        data class Tick(val millis: Long) : Operation
        data object Reopen : Operation
    }

    /** Mostly appends, so that the queue fills and evicts in most runs rather than being drained. */
    private val operation: Arb<Operation> =
        Arb.bind(Arb.int(0..9), Arb.int(0..MAX_RECORD + 40), Arb.boolean(), Arb.int(1..12)) { kind, size, brief, n ->
            when (kind) {
                in 0..6 -> Operation.Append(size, brief)
                7 -> Operation.Acknowledge(n)
                8 -> Operation.Tick(n * 10L)
                else -> Operation.Reopen
            }
        }

    /**
     * What the queue should hold, derived from the spec rather than from the code: the file is the header plus every
     * record's frame; anything over the record cap is refused; a record that would not fit first drops expired ones,
     * then the oldest, down to three quarters of the cap or to what leaves room for it; reading and reopening remove
     * expired records.
     */
    private class Model {
        class Record(val payload: ByteArray, val enqueuedAt: Long, val ttl: Long) {
            val frame = FRAME_OVERHEAD + payload.size
            fun expired(now: Long) = now - enqueuedAt >= ttl || enqueuedAt - now > ttl
        }

        val records = ArrayDeque<Record>()
        var evicted = 0L
        var expired = 0L
        var oversize = 0L
        val fileBytes: Long get() = HEADER_SIZE + records.sumOf { it.frame.toLong() }

        fun append(payload: ByteArray, ttl: Long, now: Long) {
            if (payload.size > MAX_RECORD) {
                oversize++
                return
            }
            val record = Record(payload, now, ttl)
            if (fileBytes + record.frame > MAX_FILE) {
                dropExpired(now)
                val budget = minOf(MAX_FILE / 4 * 3, MAX_FILE - record.frame)
                while (fileBytes > budget) {
                    records.removeFirst()
                    evicted++
                }
            }
            records.addLast(record)
        }

        fun dropExpired(now: Long) {
            val before = records.size
            records.removeAll { it.expired(now) }
            expired += before - records.size
        }
    }

    @Test
    fun `the queue holds exactly what the model says, within the cap, and every loss is counted once`() {
        var runsThatEvicted = 0
        var runsThatExpired = 0
        runBlocking {
            checkAll(RUNS, Arb.list(operation, 1..150)) { operations ->
                val directory = temporary.newFolder()
                val file = File(directory, "queue")
                val dropped = DroppedEvents.open(File(directory, "dropped"))
                val limits = EventQueue.Limits(maxFileBytes = MAX_FILE, maxRecordBytes = MAX_RECORD)
                val clock = MutableClock(1_000_000)
                var queue = EventQueue.open(file, dropped, limits, clock)
                val model = Model()
                var appended = 0

                for (op in operations) {
                    when (op) {
                        is Operation.Append -> {
                            val payload = ByteArray(op.size) { (appended + it).toByte() }
                            appended++
                            val ttl = if (op.shortLived) SHORT_TTL else LONG_TTL
                            queue.append(payload, ttl)
                            model.append(payload, ttl, clock.now)
                        }
                        is Operation.Acknowledge -> {
                            val batch = queue.peek(op.count, Int.MAX_VALUE)
                            queue.remove(batch)
                            model.dropExpired(clock.now)
                            repeat(minOf(op.count, model.records.size)) { model.records.removeFirst() }
                        }
                        is Operation.Tick -> clock.now += op.millis
                        Operation.Reopen -> {
                            queue.close()
                            queue = EventQueue.open(file, dropped, limits, clock)
                            model.dropExpired(clock.now)
                        }
                    }

                    // Reading the queue removes expired records, so the model does the same before comparing.
                    val actual = queue.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload }
                    model.dropExpired(clock.now)
                    assertTrue(actual.sameAs(model.records.map { it.payload }))
                    assertEquals(model.fileBytes, file.length())
                    assertTrue(file.length() <= MAX_FILE)
                    val counts = dropped.snapshot()
                    assertEquals(model.evicted, counts[DropReason.EVICTED] ?: 0L)
                    assertEquals(model.expired, counts[DropReason.EXPIRED] ?: 0L)
                    assertEquals(model.oversize, counts[DropReason.OVERSIZE] ?: 0L)
                    assertEquals(emptySet<DropReason>(), counts.keys - MODELLED_REASONS)
                }
                if (model.evicted > 0) runsThatEvicted++
                if (model.expired > 0) runsThatExpired++
            }
        }
        // Guards against a generator that quietly stops reaching the interesting cases.
        assertTrue("only $runsThatEvicted of $RUNS runs evicted", runsThatEvicted > RUNS / 3)
        assertTrue("only $runsThatExpired of $RUNS runs expired", runsThatExpired > RUNS / 3)
    }

    private sealed interface Damage {
        data class Cut(val fromEnd: Int) : Damage
        data class Garbage(val bytes: ByteArray) : Damage
        data class Zeros(val count: Int) : Damage
        data class Flip(val position: Int) : Damage
    }

    private val damage: Arb<Damage> = Arb.choice(
        Arb.int(0..400).map { Damage.Cut(it) },
        Arb.byteArray(Arb.int(1..80), Arb.byte()).map { Damage.Garbage(it) },
        Arb.int(1..80).map { Damage.Zeros(it) },
        Arb.int(0..Int.MAX_VALUE).map { Damage.Flip(it) },
    )

    private val records: Arb<List<ByteArray>> =
        Arb.list(Arb.byteArray(Arb.int(0..120), Arb.byte()), 0..20)

    @Test
    fun `damage restores exactly the records before it, counts it once, and a record written after it survives`() {
        runBlocking {
            checkAll(2_000, Arb.bind(records, damage) { r, d -> r to d }) { (written, harm) ->
                val directory = temporary.newFolder()
                val file = File(directory, "queue")
                val dropped = DroppedEvents.open(File(directory, "dropped"))
                EventQueue.open(file, dropped, clock = MutableClock(0)).apply {
                    written.forEach { append(it, LONG_TTL) }
                    close()
                }
                val ends = written.runningFold(HEADER_SIZE) { end, record -> end + FRAME_OVERHEAD + record.size }

                // How many records lie wholly before the damage, and whether any bytes of it remain in the file.
                val (intact, damaged) = RandomAccessFile(file, "rw").use { raw ->
                    val length = raw.length()
                    when (harm) {
                        is Damage.Cut -> {
                            val remaining = maxOf(HEADER_SIZE, length - harm.fromEnd)
                            raw.setLength(remaining)
                            val whole = ends.count { it <= remaining } - 1
                            whole to (remaining > ends[whole])
                        }
                        is Damage.Garbage -> {
                            raw.seek(length)
                            raw.write(harm.bytes)
                            written.size to true
                        }
                        is Damage.Zeros -> {
                            raw.seek(length)
                            raw.write(ByteArray(harm.count))
                            written.size to true
                        }
                        is Damage.Flip -> {
                            if (length == HEADER_SIZE) return@use 0 to false
                            val position = HEADER_SIZE + harm.position % (length - HEADER_SIZE)
                            raw.seek(position)
                            val original = raw.read()
                            raw.seek(position)
                            raw.write(original xor 0xFF)
                            (ends.count { it <= position } - 1) to true
                        }
                    }
                }

                val restored = EventQueue.open(file, dropped, clock = MutableClock(0))
                val expected = written.take(intact)
                assertTrue(restored.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload }.sameAs(expected))
                assertEquals(ends[intact], file.length())
                assertEquals(if (damaged) 1L else 0L, dropped.snapshot()[DropReason.CORRUPT] ?: 0L)

                val marker = "after the damage".encodeToByteArray()
                restored.append(marker, LONG_TTL)
                restored.close()
                val reopened = EventQueue.open(file, dropped, clock = MutableClock(0))
                val afterwards = reopened.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload }
                assertTrue(afterwards.sameAs(expected + listOf(marker)))
            }
        }
    }

    private fun List<ByteArray>.sameAs(other: List<ByteArray>): Boolean =
        size == other.size && zip(other).all { (a, b) -> a.contentEquals(b) }

    private companion object {
        const val RUNS = 500
        const val MAX_FILE = 1_024
        const val MAX_RECORD = 200
        const val SHORT_TTL = 50L
        const val LONG_TTL = Long.MAX_VALUE / 4
        const val HEADER_SIZE = 4L
        const val FRAME_OVERHEAD = 24
        val MODELLED_REASONS = setOf(DropReason.EVICTED, DropReason.EXPIRED, DropReason.OVERSIZE)
    }
}
