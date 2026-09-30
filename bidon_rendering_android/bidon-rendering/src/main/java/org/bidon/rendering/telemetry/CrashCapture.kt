package org.bidon.rendering.telemetry

/**
 * The process's default uncaught exception handler while telemetry is on. It records crashes that passed through our
 * code and then hands every crash, ours or not, to the handler that was installed before it.
 *
 * Host apps have crash reporters of their own, installed the same way. This handler wraps whichever one was there and
 * always calls it, whatever happens while recording, so the host's reporter sees every crash and the process dies
 * exactly as it would without us. A reporter installed after this one wraps it in turn, and reaches it as long as it
 * calls the handler it replaced, which crash reporters do.
 *
 * Uncaught exceptions from coroutines and executor threads reach this handler too, as long as nothing on the way
 * catches them: an uncaught coroutine exception is passed to its thread's handler, and a thread without one of its own
 * uses the default.
 *
 * @param previous the handler to call after recording, `null` when there was none. Android installs one of its own in
 * every app process before any app code runs, the one that kills the process, so on a device there always is one.
 * @param slot where the record goes, resolved only when a crash happens, because resolving it may touch the disk.
 */
internal class CrashCapture(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val report: CrashReport,
    private val mainThread: Thread,
    private val slot: () -> CrashSlot,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            record(thread, throwable)
        } catch (_: Throwable) {
            // Recording is best effort: whatever went wrong, the crash still goes on to the previous handler.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(thread: Thread, throwable: Throwable) {
        if (!report.isOwn(throwable)) return
        val record = report.encode(throwable, onMainThread = thread === mainThread) ?: return
        slot().write(record)
    }

    companion object {

        /**
         * Makes a [CrashCapture] the process's default handler, wrapping the current one. Does nothing when the
         * current one already is a [CrashCapture], so a second call cannot record a crash twice.
         */
        fun install(create: (previous: Thread.UncaughtExceptionHandler?) -> CrashCapture) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashCapture) return
            Thread.setDefaultUncaughtExceptionHandler(create(current))
        }
    }
}
