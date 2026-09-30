package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashSlotTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val directory by lazy { temporary.newFolder() }
    private val store = RecordingStore()

    @Test
    fun `a crash written by one process reaches the next one's store as an error, and leaves no file behind`() {
        CrashSlot(directory).write("crash".encodeToByteArray())

        CrashSlot(directory).moveInto(store)

        assertEquals(listOf("crash" to EventClass.ERROR), store.appended)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun `with no crash left there is nothing to move`() {
        CrashSlot(directory).moveInto(store)

        assertEquals(emptyList<Pair<String, EventClass>>(), store.appended)
    }

    @Test
    fun `the slot is created with its directory when the store never got to open`() {
        val missing = File(directory, "bidon_telemetry/com.app")

        CrashSlot(missing).write("crash".encodeToByteArray())
        CrashSlot(missing).moveInto(store)

        assertEquals(listOf("crash" to EventClass.ERROR), store.appended)
    }

    @Test
    fun `a later crash replaces one that was not moved yet`() {
        CrashSlot(directory).write("first".encodeToByteArray())
        CrashSlot(directory).write("second".encodeToByteArray())

        CrashSlot(directory).moveInto(store)

        assertEquals(listOf("second" to EventClass.ERROR), store.appended)
    }

    @Test
    fun `a crash written while the previous one is being moved is kept for the next launch`() {
        val slot = CrashSlot(directory)
        slot.write("earlier".encodeToByteArray())
        store.onAppend = { slot.write("during".encodeToByteArray()) }

        slot.moveInto(store)
        store.onAppend = {}
        CrashSlot(directory).moveInto(store)

        assertEquals(listOf("earlier", "during"), store.appended.map { it.first })
    }

    @Test
    fun `a crash claimed by a process that died while moving it is moved by the next one`() {
        File(directory, "crash.claimed").writeText("interrupted")
        CrashSlot(directory).write("newer".encodeToByteArray())

        CrashSlot(directory).moveInto(store)

        assertEquals(listOf("interrupted", "newer"), store.appended.map { it.first })
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun `an empty slot, left by a write that never happened, is removed and not stored`() {
        File(directory, "crash").createNewFile()

        CrashSlot(directory).moveInto(store)

        assertTrue(store.appended.isEmpty())
        assertFalse(File(directory, "crash").exists())
        assertFalse(File(directory, "crash.claimed").exists())
    }

    @Test
    fun `a moved crash is in the disk store's error queue, not with the diagnostics`() {
        CrashSlot(directory).write("crash".encodeToByteArray())

        DiskEventStore.open(directory).also { CrashSlot(directory).moveInto(it) }

        val dropped = DroppedEvents.open(File(directory, "dropped"))
        val errors = EventQueue.open(File(directory, "errors"), dropped)
        val diagnostics = EventQueue.open(File(directory, "queue"), dropped)
        assertEquals(listOf("crash"), errors.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload.decodeToString() })
        assertTrue(diagnostics.peek(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    private class RecordingStore : EventStore {

        val appended = mutableListOf<Pair<String, EventClass>>()

        var onAppend: () -> Unit = {}

        override fun append(payload: ByteArray, eventClass: EventClass) {
            appended += payload.decodeToString() to eventClass
            onAppend()
        }

        override fun sync() = Unit

        override fun countDropped(reason: DropReason, count: Long) = Unit
    }
}
