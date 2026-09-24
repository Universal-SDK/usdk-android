package org.bidon.rendering.telemetry

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * What restoring the queue costs, measured on the device rather than assumed.
 *
 * Restore runs on the queue's own thread, never on the ad path, but it still delays the first telemetry record of a
 * launch, so its cost is budgeted: in a running process a full queue must restore in under 20 ms at the 95th
 * percentile. Results are logged under [TAG].
 *
 * These runs read a file the page cache already holds, in a process that has already run the code.
 * [coldRestoreOfAFullQueue] measures the first restore in a fresh process, optionally from storage, and is driven
 * from the host; see its KDoc. It is not budgeted here: a test APK is debuggable, and ART runs debuggable code without
 * its optimising compiler, so a fresh process measures the interpreter rather than what a release app would see.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCostTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val root = File(context.cacheDir, "restore-cost")

    @Test
    fun aFullQueueRestoresWithinBudget() {
        val sizes = listOf(0, 64 * 1024, 256 * 1024, Int.MAX_VALUE)
        var fullP95Millis = Double.NaN
        for (size in sizes) {
            val directory = File(root, "warm-$size").apply { deleteRecursively() }
            val records = fill(directory, size)
            val bytes = File(directory, QUEUE).length()

            val millis = (1..RUNS).map { measureRestoreMillis(directory, records) }.sorted()
            val median = millis[RUNS / 2]
            val p95 = millis[(RUNS * 95 + 99) / 100 - 1]
            Log.i(TAG, format("warm bytes=%d records=%d median=%.3fms p95=%.3fms", bytes, records, median, p95))
            if (size == Int.MAX_VALUE) fullP95Millis = p95
        }
        assertTrue("a full queue took ${fullP95Millis}ms at p95", fullP95Millis < BUDGET_MILLIS)
    }

    /**
     * One restore of a full queue from storage. Skipped unless the instrumentation argument `coldRestore` is `true`.
     * The host prepares the file with a first run, then for each measurement drops the page cache as root and runs
     * this test again:
     *
     * ```
     * adb root
     * adb shell "sync; echo 3 > /proc/sys/vm/drop_caches"
     * adb shell am instrument -w -e coldRestore true \
     *   -e class org.bidon.rendering.telemetry.RestoreCostTest#coldRestoreOfAFullQueue \
     *   org.bidon.rendering.test/androidx.test.runner.AndroidJUnitRunner
     * ```
     */
    @Test
    fun coldRestoreOfAFullQueue() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coldRestore") == "true")
        val directory = File(root, "cold")
        if (!File(directory, QUEUE).exists()) {
            assertEquals(FULL_RECORDS, fill(directory, Int.MAX_VALUE))
            Log.i(TAG, "cold prepared records=$FULL_RECORDS; run again after dropping the page cache")
            return
        }
        val millis = measureRestoreMillis(directory, expectedRecords = FULL_RECORDS)
        Log.i(TAG, format("cold bytes=%d restore=%.3fms", File(directory, QUEUE).length(), millis))
    }

    /** Fills a queue with 500-byte records until the next one would exceed [targetBytes] or the cap. */
    private fun fill(directory: File, targetBytes: Int): Int {
        val queue = EventQueue.open(File(directory, QUEUE), DroppedEvents.open(File(directory, DROPPED)))
        val limit = minOf(targetBytes.toLong(), EventQueue.Limits().maxFileBytes.toLong())
        val payload = ByteArray(500) { 'x'.code.toByte() }
        var records = 0
        while (File(directory, QUEUE).length() + payload.size + FRAME_OVERHEAD <= limit) {
            queue.append(payload, TTL)
            records++
        }
        queue.close()
        return records
    }

    /** Times one open, and checks it restored every record, so that a queue that lost them cannot look fast. */
    private fun measureRestoreMillis(directory: File, expectedRecords: Int): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        val queue = EventQueue.open(File(directory, QUEUE), DroppedEvents.open(File(directory, DROPPED)))
        val elapsed = SystemClock.elapsedRealtimeNanos() - started
        assertEquals(expectedRecords, queue.size)
        queue.close()
        return elapsed / 1_000_000.0
    }

    private fun format(pattern: String, vararg args: Any) = String.format(Locale.ROOT, pattern, *args)

    private companion object {
        const val TAG = "BidonTelemetryRestore"
        const val QUEUE = "queue"
        const val DROPPED = "dropped"
        const val RUNS = 20
        /** 500-byte records that fit under the default 512 KiB cap. */
        const val FULL_RECORDS = 1_000
        const val BUDGET_MILLIS = 20.0
        const val FRAME_OVERHEAD = 24
        const val TTL = 24 * 60 * 60 * 1_000L
    }
}
