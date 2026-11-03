package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DroppedEventsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val file by lazy { File(temporary.newFolder(), "dropped") }

    @Test
    fun `counts survive being reopened`() {
        DroppedEvents.open(file).apply {
            add(DropReason.EVICTED, 3)
            add(DropReason.EXPIRED, 1)
            add(DropReason.EVICTED, 2)
        }

        assertEquals(
            mapOf(DropReason.EVICTED to 5L, DropReason.EXPIRED to 1L),
            DroppedEvents.open(file).snapshot(),
        )
    }

    @Test
    fun `every reason survives being reopened under its own count`() {
        DroppedEvents.open(file).apply {
            DropReason.entries.forEachIndexed { index, reason -> add(reason, index + 1L) }
        }

        assertEquals(
            DropReason.entries.mapIndexed { index, reason -> reason to index + 1L }.toMap(),
            DroppedEvents.open(file).snapshot(),
        )
    }

    @Test
    fun `subtracting a reported snapshot keeps what was counted after it was taken`() {
        val counts = DroppedEvents.open(file)
        counts.add(DropReason.EVICTED, 4)
        val reported = counts.snapshot()
        counts.add(DropReason.EVICTED, 2)
        counts.add(DropReason.IO_ERROR, 1)

        counts.subtract(reported)

        assertEquals(mapOf(DropReason.EVICTED to 2L, DropReason.IO_ERROR to 1L), counts.snapshot())
        assertEquals(counts.snapshot(), DroppedEvents.open(file).snapshot())
    }

    @Test
    fun `subtracting more than is counted stops at zero`() {
        val counts = DroppedEvents.open(file)
        counts.add(DropReason.CORRUPT, 1)

        counts.subtract(mapOf(DropReason.CORRUPT to 5L))

        assertEquals(emptyMap<DropReason, Long>(), counts.snapshot())
    }

    @Test
    fun `zero and negative additions change nothing`() {
        val counts = DroppedEvents.open(file)
        counts.add(DropReason.OVERSIZE, 0)
        counts.add(DropReason.OVERSIZE, -3)

        assertEquals(emptyMap<DropReason, Long>(), counts.snapshot())
    }

    @Test
    fun `unreadable lines are ignored and the readable ones kept`() {
        file.writeText("evicted=7\nexpired=abc\n\u0000\u0000garbage\noversize=-2\nunknown=9\ncorrupt=1\n")

        assertEquals(
            mapOf(DropReason.EVICTED to 7L, DropReason.CORRUPT to 1L),
            DroppedEvents.open(file).snapshot(),
        )
    }
}
