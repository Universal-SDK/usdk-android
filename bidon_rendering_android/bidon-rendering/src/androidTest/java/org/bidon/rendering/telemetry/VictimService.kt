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
import org.bidon.rendering.TelemetryDirectory
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Runs in its own `:victim` process and owns a telemetry queue there, the way a host process would, so that a test in
 * the main process can kill it at a chosen moment and then read back what survived.
 *
 * Every command runs on one thread, which is how the queue's owner is required to use it. Each reply carries this
 * process's pid in `arg1`.
 */
class VictimService : Service() {

    private val worker = HandlerThread("victim-queue").apply { start() }
    private val messenger = Messenger(Handler(worker.looper) { handle(it); true })

    private val directory by lazy { TelemetryDirectory.forCurrentProcess(this) }
    private val dropped by lazy { DroppedEvents.open(File(directory, "dropped")) }
    private val queue by lazy { EventQueue.open(File(directory, "queue"), dropped) }

    /**
     * The index of the last storm record whose append has returned. Written after each append, so it can only lag
     * the queue, and it survives a kill the same way the queue does. It lets the test tell a lost record from one
     * that was never written.
     */
    private val progress by lazy { RandomAccessFile(File(directory, "progress"), "rw") }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        val replyTo = message.replyTo
        val reply = Message.obtain(null, message.what, Process.myPid(), 0)
        when (message.what) {
            CLEAR -> {
                queue.remove(queue.peek(Int.MAX_VALUE, Int.MAX_VALUE))
                progress.setLength(0)
            }
            APPEND -> repeat(message.arg1) { queue.append("record-$it".encodeToByteArray(), TTL) }
            READ -> reply.data = Bundle().apply {
                putString(DIRECTORY, directory.path)
                putStringArrayList(
                    PAYLOADS,
                    ArrayList(queue.peek(Int.MAX_VALUE, Int.MAX_VALUE).map { it.payload.decodeToString() }),
                )
                putLong(EVICTED, dropped.snapshot()[DropReason.EVICTED] ?: 0L)
                putInt(ACKNOWLEDGED, if (progress.length() >= 4) progress.apply { seek(0) }.readInt() else -1)
            }
            STORM -> {
                // Reply once writing is well under way, then keep writing until the process is killed.
                var index = 0
                while (index < STORM_HEAD_START) storm(index++)
                replyTo.send(reply)
                while (true) storm(index++)
            }
        }
        replyTo.send(reply)
    }

    private fun storm(index: Int) {
        check(queue.append("storm-$index".encodeToByteArray(), TTL)) { "append $index was refused" }
        progress.seek(0)
        // One write, so a kill cannot land between the bytes of the number.
        progress.write(ByteBuffer.allocate(4).putInt(index).array())
    }

    companion object {
        const val CLEAR = 1
        const val APPEND = 2
        const val READ = 3
        const val STORM = 4

        const val DIRECTORY = "directory"
        const val PAYLOADS = "payloads"
        const val EVICTED = "evicted"
        const val ACKNOWLEDGED = "acknowledged"

        const val STORM_HEAD_START = 50
        private const val TTL = 60 * 60 * 1_000L
    }
}
