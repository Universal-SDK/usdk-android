package org.bidon.rendering.telemetry

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.RendererTelemetry
import org.bidon.rendering.TelemetryDirectory
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in its own `:crash_victim` process and crashes it on command, the way an app with the renderer and a crash
 * reporter of its own would crash.
 *
 * Every crash command starts from an empty telemetry directory, installs [HostCrashReporter] the way a host app
 * installs its crash reporter, before the SDK, then initialises the renderer and crashes. [READ] starts the renderer
 * in the next process and reports what reached its error queue.
 *
 * The process ends only once the test has unbound and the service is destroyed: the crash, or after [READ] a plain
 * exit. A bound service whose process dies is scheduled for a restart, with a delay that grows with each death, and
 * a new bind waits for that restart, so ending the process while still bound made the next test wait at random.
 */
class CrashVictimService : Service() {

    private val worker = HandlerThread("crash-victim").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { handle(it); true })

    private val directory by lazy { TelemetryDirectory.forCurrentProcess(this) }
    private val hostReport by lazy { File(filesDir, "host_crash_report") }

    /** The command this process ends with once the service is destroyed. */
    @Volatile
    private var ending: Int? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        val reply = Message.obtain(null, message.what, Process.myPid(), 0)
        when (message.what) {
            CRASH_IN_OUR_THREAD, CRASH_IN_HOST_CODE, CRASH_IN_OUR_COROUTINE -> {
                directory.deleteRecursively()
                hostReport.delete()
                HostCrashReporter.install(hostReport)
                BidonRenderer.init(this)
                reply.data = Bundle().apply { putString(SESSION, RendererTelemetry.sessionId.value) }
            }
            READ -> reply.data = read()
        }
        ending = message.what
        message.replyTo.send(reply)
    }

    override fun onDestroy() {
        super.onDestroy()
        when (val how = ending) {
            null -> Unit
            READ -> Process.killProcess(Process.myPid())
            else -> crash(how)
        }
    }

    private fun crash(how: Int) {
        when (how) {
            CRASH_IN_OUR_THREAD -> Thread({ throw IllegalStateException("ours") }, "renderer-work").start()
            CRASH_IN_HOST_CODE -> Thread({ throw hostException() }, "game-loop").start()
            CRASH_IN_OUR_COROUTINE -> CoroutineScope(Dispatchers.Main).launch { throw IllegalStateException("ours") }
        }
    }

    /** What reached the error queue once the renderer has started in this process and moved the last crash in. */
    private fun read(): Bundle {
        BidonRenderer.init(this)
        val deadline = System.currentTimeMillis() + 5_000
        while (File(directory, "crash").exists() || File(directory, "crash.claimed").exists()) {
            check(System.currentTimeMillis() < deadline) { "the crash slot was not moved" }
            Thread.sleep(10)
        }
        val errors = File(directory, "errors")
        val events = if (errors.exists()) {
            EventQueue.open(errors, DroppedEvents.open(File(directory, "read_dropped")))
                .peek(Int.MAX_VALUE, Int.MAX_VALUE)
                .map { it.payload.decodeToString() }
        } else {
            emptyList()
        }
        return Bundle().apply {
            putStringArrayList(ERRORS, ArrayList(events))
            putString(HOST_REPORT, hostReport.takeIf { it.exists() }?.readText())
            putString(SESSION, RendererTelemetry.sessionId.value)
        }
    }

    /** An exception thrown from the host app's code: nothing of ours in its stack. */
    private fun hostException() = IllegalStateException("host").apply {
        stackTrace = arrayOf(
            StackTraceElement("com.publisher.game.Level", "update", "Level.kt", 42),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
        )
    }

    /**
     * Stands in for a host app's crash reporter: it notes the crash in a file, then ends the process itself, as some
     * reporters do. Handing the crash on to the system instead would bring in its crash handling, which keeps a
     * process that crashes again within a minute alive behind a dialog and then refuses to restart it, and these
     * tests crash the same process several times in a row.
     */
    private class HostCrashReporter(private val report: File) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            report.writeText("${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }

        companion object {
            fun install(report: File) = Thread.setDefaultUncaughtExceptionHandler(HostCrashReporter(report))
        }
    }

    companion object {
        const val CRASH_IN_OUR_THREAD = 1
        const val CRASH_IN_HOST_CODE = 2
        const val CRASH_IN_OUR_COROUTINE = 3
        const val READ = 4

        const val ERRORS = "errors"
        const val HOST_REPORT = "host_report"
        const val SESSION = "session"
    }
}
