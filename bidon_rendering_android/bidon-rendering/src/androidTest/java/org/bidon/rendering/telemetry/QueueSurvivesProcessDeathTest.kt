package org.bidon.rendering.telemetry

import android.content.Context
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.TelemetryDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * The queue's reason to exist: records written before the process dies are there when it comes back.
 *
 * The queue lives in [VictimService], in a `:victim` process of its own, so the test can kill that process and start
 * a new one. Two kinds of death are covered. `am kill` is what the system does to an app sitting in the background.
 * SIGKILL in the middle of a stream of writes is the worst moment a death can pick, and must still leave every record
 * before it whole.
 */
@RunWith(AndroidJUnit4::class)
class QueueSurvivesProcessDeathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.context

    @Test
    fun recordsWrittenBeforeAnAmKillAreRestoredByTheNextProcess() {
        val killed = withVictim {
            request(VictimService.CLEAR)
            request(VictimService.APPEND, arg1 = 200).arg1
        }

        killInBackground(killed)
        val restored = withVictim { request(VictimService.READ) }

        assertNotEquals("the victim was not restarted", killed, restored.arg1)
        assertEquals(
            (0 until 200).map { "record-$it" },
            restored.data.getStringArrayList(VictimService.PAYLOADS),
        )
    }

    @Test
    fun aKillInTheMiddleOfWritingLeavesEveryRecordBeforeItWhole() {
        repeat(5) { round ->
            var evictedBefore = 0L
            val killed = withVictim {
                request(VictimService.CLEAR)
                evictedBefore = request(VictimService.READ).data.getLong(VictimService.EVICTED)
                request(VictimService.STORM).arg1
            }
            val sleptMillis = Random.nextLong(5, 600)
            val label = "round $round (killed after ${sleptMillis}ms)"
            Thread.sleep(sleptMillis)
            Process.killProcess(killed)
            awaitDeath(killed)

            val restored = withVictim { request(VictimService.READ) }.data
            val indices = restored.getStringArrayList(VictimService.PAYLOADS)!!.map { payload ->
                assertTrue("$label: damaged record '$payload'", payload.matches(Regex("storm-\\d+")))
                payload.removePrefix("storm-").toInt()
            }
            val acknowledged = restored.getInt(VictimService.ACKNOWLEDGED)
            assertTrue(
                "$label: append returned for $acknowledged, restored up to ${indices.last()}",
                acknowledged >= VictimService.STORM_HEAD_START - 1 && indices.last() >= acknowledged,
            )
            val first = indices.first()
            assertEquals("$label: not one unbroken run", (first..indices.last()).toList(), indices)
            // Records missing from the front were evicted. A death between a compaction and the write of its count
            // can leave that one eviction uncounted, never the other way round.
            val evicted = restored.getLong(VictimService.EVICTED) - evictedBefore
            assertTrue("$label: $evicted counted as evicted, $first missing", evicted <= first)
            Log.i(
                "BidonTelemetryQueue",
                "$label: acknowledged up to $acknowledged, restored $first..${indices.last()}, $evicted evicted",
            )
        }
    }

    @Test
    fun eachProcessKeepsItsQueueInItsOwnDirectory() {
        val victimDirectory = withVictim { request(VictimService.READ) }.data.getString(VictimService.DIRECTORY)!!
        val ownDirectory = TelemetryDirectory.forCurrentProcess(context).path

        assertNotEquals(ownDirectory, victimDirectory)
        assertTrue(victimDirectory, victimDirectory.endsWith("_victim"))
    }

    /**
     * `am kill` only kills a process the system considers safe to kill, which the victim becomes shortly after the
     * last client unbinds. Retried until then.
     */
    private fun killInBackground(pid: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (File("/proc/$pid").exists()) {
            if (System.currentTimeMillis() > deadline) fail("am kill did not kill the victim ($pid)")
            shell("am kill ${context.packageName}")
            Thread.sleep(200)
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun <T> withVictim(block: Messenger.() -> T): T = withService<VictimService, T>(context, block)
}
