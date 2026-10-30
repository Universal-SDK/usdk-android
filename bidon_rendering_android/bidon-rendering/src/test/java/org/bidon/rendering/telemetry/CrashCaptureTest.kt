package org.bidon.rendering.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The three things a crash handler inside someone else's app must get right: it records crashes in our code, it
 * ignores the host's, and the handler that was there before it runs every time, whatever happens while recording.
 */
class CrashCaptureTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val directory by lazy { temporary.newFolder() }
    private val previous = RecordingHandler()
    private val session = SessionId.of("550e8400-e29b-41d4-a716-446655440000")!!
    private val report = CrashReport(listOf("org.bidon.rendering."), session, Clock { 1_000 }, { 0L }, { null })
    private val mainThread = Thread.currentThread()

    private val originalDefault = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restoreDefaultHandler() = Thread.setDefaultUncaughtExceptionHandler(originalDefault)

    private fun capture(
        previous: Thread.UncaughtExceptionHandler? = this.previous,
        slot: () -> CrashSlot = { CrashSlot(directory) },
    ) = CrashCapture(previous, report, mainThread, slot)

    @Test
    fun `a crash in our code is recorded, then handed to the previous handler`() {
        val crash = ownCrash()
        val thread = Thread()

        capture().uncaughtException(thread, crash)

        val event = movedEvents().single()
        assertEquals("sdk_crashed", event.getJSONObject("envelope").getString("event_name"))
        assertEquals(session.value, event.getJSONObject("envelope").getString("session_id"))
        assertFalse(event.getBoolean("crashed_on_main_thread"))
        assertEquals(listOf(thread to crash), previous.calls)
    }

    @Test
    fun `a crash on the main thread is recorded as one`() {
        capture().uncaughtException(mainThread, ownCrash())

        assertTrue(movedEvents().single().getBoolean("crashed_on_main_thread"))
    }

    @Test
    fun `the host app's crash is not recorded, and still reaches the previous handler`() {
        val crash = IllegalStateException().apply {
            stackTrace = arrayOf(StackTraceElement("com.game.Level", "load", "Level.kt", 3))
        }

        capture().uncaughtException(Thread.currentThread(), crash)

        assertTrue(movedEvents().isEmpty())
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        assertEquals(listOf(Thread.currentThread() to crash), previous.calls)
    }

    @Test
    fun `when the record cannot be written, the previous handler still runs and nothing escapes`() {
        val notADirectory = temporary.newFile()
        val crash = ownCrash()

        capture(slot = { CrashSlot(notADirectory) }).uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf(Thread.currentThread() to crash), previous.calls)
    }

    @Test
    fun `when describing the crash itself fails, the previous handler still runs and nothing escapes`() {
        val hostile = object : RuntimeException() {
            override fun getStackTrace(): Array<StackTraceElement> = throw OutOfMemoryError("while describing")
        }

        capture().uncaughtException(Thread.currentThread(), hostile)

        assertEquals(listOf(Thread.currentThread() to hostile), previous.calls)
    }

    @Test
    fun `when the uptime source and the integration supplier throw, the previous handler still runs and nothing escapes`() {
        val failing = CrashReport(
            listOf("org.bidon.rendering."),
            session,
            Clock { 1_000 },
            { throw IllegalStateException("no uptime") },
            { throw IllegalStateException("no integration") },
        )
        val crash = ownCrash()

        CrashCapture(previous, failing, mainThread) { CrashSlot(directory) }
            .uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf(Thread.currentThread() to crash), previous.calls)
    }

    @Test
    fun `with no previous handler a crash is still recorded and nothing escapes`() {
        capture(previous = null).uncaughtException(Thread.currentThread(), ownCrash())

        assertEquals(1, movedEvents().size)
    }

    @Test
    fun `install wraps the handler that was there, and a second install does not wrap it again`() {
        Thread.setDefaultUncaughtExceptionHandler(previous)

        CrashCapture.install { capture(previous = it) }
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        CrashCapture.install { capture(previous = it) }

        assertTrue(installed is CrashCapture)
        assertNotSame(previous, installed)
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
        installed!!.uncaughtException(Thread.currentThread(), ownCrash())
        assertEquals(1, previous.calls.size)
        assertEquals(1, movedEvents().size)
    }

    @Test
    fun `a thread that dies of an exception in our code reaches the installed handler`() {
        install()

        Thread { throw ownCrash() }.apply { start() }.join()

        assertEquals(1, movedEvents().size)
        assertEquals(1, previous.calls.size)
    }

    @Test
    fun `an exception that escapes an executor task reaches the installed handler`() {
        install()
        val executor = Executors.newSingleThreadExecutor()

        executor.execute { throw ownCrash() }

        awaitUntil { previous.calls.isNotEmpty() }
        executor.shutdown()
        assertEquals(1, movedEvents().size)
    }

    @Test
    fun `an exception that escapes a coroutine reaches the installed handler`() {
        install()

        CoroutineScope(Dispatchers.Default).launch { throw ownCrash() }

        awaitUntil { previous.calls.isNotEmpty() }
        assertEquals(1, movedEvents().size)
    }

    private fun install() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        CrashCapture.install { capture(previous = it) }
    }

    /** What the slot holds, moved the way the next launch moves it. */
    private fun movedEvents(): List<JSONObject> {
        val store = object : WriteOnlyStore() {
            val events = mutableListOf<JSONObject>()
            override fun append(payload: ByteArray, eventClass: EventClass) {
                assertEquals(EventClass.ERROR, eventClass)
                events += JSONObject(payload.decodeToString())
            }

            override fun sync() = Unit
            override fun countDropped(reason: DropReason, count: Long) = Unit
        }
        if (directory.isDirectory) CrashSlot(directory).moveInto(store)
        return store.events
    }

    /** An exception thrown in our code, wherever it is actually thrown from. */
    private fun ownCrash() = IllegalStateException().apply {
        stackTrace = arrayOf(StackTraceElement("org.bidon.rendering.vast.VastRenderer", "load", "VastRenderer.kt", 60))
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(5)
        }
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {

        val calls: MutableList<Pair<Thread, Throwable>> = CopyOnWriteArrayList()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            calls += thread to throwable
        }
    }
}
