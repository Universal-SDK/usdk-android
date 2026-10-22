package org.bidon.rendering

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.telemetry.DroppedEvents
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.SettingsFile
import org.bidon.rendering.telemetry.TelemetryEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Telemetry does no disk work on the main thread: starting it (which restores the queue from disk), emitting,
 * syncing and configuring it all leave the main thread without a single StrictMode violation raised by our code.
 *
 * Violations raised by other code are ignored, so this test fails on ours and nothing else. The test APK's manifest
 * switches telemetry on.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
class TelemetryOffTheMainThreadTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun startingEmittingAndSyncingRaiseNoViolationOnTheMainThread() {
        val stored = CountDownLatch(1)

        val violations = violationsOnMainThread {
            // Its own files: the renderer's telemetry, which the test below starts in this process, keeps the app's.
            val telemetry = RendererTelemetry.start(WithOwnStorage(context))
            // Fewer than the handoff holds, so none is dropped while the queue is still being restored.
            repeat(100) { index ->
                telemetry.emit("event_$index", EventClass.DIAGNOSTIC) {
                    TelemetryEvent { "event $index".encodeToByteArray() }
                }
            }
            // Encoded on the telemetry thread once everything before it has been written.
            telemetry.emit("stored", EventClass.DIAGNOSTIC) {
                TelemetryEvent {
                    stored.countDown()
                    ByteArray(0)
                }
            }
            telemetry.sync()
        }

        assertTrue(
            "events never stored: the test manifest lacks the flag, or telemetry failed (see logcat BidonTelemetry)",
            stored.await(10, TimeUnit.SECONDS),
        )
        assertEquals(emptyList<String>(), violations.filter { it.isRaisedByTelemetry() }.map { it.describe() })
    }

    /**
     * Giving the renderer the server's telemetry settings, before `init` and after it, leaves the main thread at once:
     * the stored copy is written, and the endpoint used, on the telemetry thread.
     */
    @Test
    fun configuringTelemetryRaisesNoViolationOnTheMainThread() {
        val stored = File(TelemetryDirectory.forCurrentProcess(context), SettingsFile.FILE_NAME)
        // What an earlier run wrote must not pass for what this one writes.
        stored.delete()

        val violations = violationsOnMainThread {
            BidonRenderer.configureTelemetry(block(sampleRate = 0.5), context.packageName, "1.0.0", null)
            BidonRenderer.init(context)
            BidonRenderer.configureTelemetry(block(sampleRate = 0.25), context.packageName, "1.0.0", null)
        }

        assertTrue("the settings were never stored", awaitContent(stored, "0.25"))
        assertEquals(emptyList<String>(), violations.filter { it.isRaisedByTelemetry() }.map { it.describe() })
    }

    /** A block with an endpoint nobody listens on, so nothing in this test process receives the events it sends. */
    private fun block(sampleRate: Double) = JSONObject()
        .put("url", "https://localhost:1/v2/telemetry")
        .put("app_key", "key")
        .put("sample_rate", sampleRate)

    private fun awaitContent(file: File, text: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains(text)) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Without this, the test above could pass because no violation of ours would ever be seen. */
    @Test
    fun diskWorkByTelemetryCodeOnTheMainThreadIsSeen() {
        val violations = violationsOnMainThread {
            DroppedEvents.open(File(context.cacheDir, "strict-mode-control"))
        }

        assertTrue(violations.any { it.isRaisedByTelemetry() })
    }

    /**
     * Runs [block] on the main thread under a policy that detects everything and reports it here.
     *
     * On a thread with a looper, StrictMode reports a violation after the current message, and it looks the listener
     * up at that moment. So the policy stays in place until the main thread has gone idle, and is only then put back
     * in a message of its own; putting it back at the end of [block] would leave nobody to report to.
     */
    private fun violationsOnMainThread(block: () -> Unit): List<Violation> {
        val violations = CopyOnWriteArrayList<Violation>()
        lateinit var previous: StrictMode.ThreadPolicy
        instrumentation.runOnMainSync {
            previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyListener(Runnable::run) { violations += it }
                    .build(),
            )
            try {
                block()
            } catch (e: Throwable) {
                StrictMode.setThreadPolicy(previous)
                throw e
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { StrictMode.setThreadPolicy(previous) }
        return violations
    }

    private fun Violation.isRaisedByTelemetry(): Boolean = stackTrace.any { it.isTelemetryCode() }

    private fun Violation.describe(): String = "${javaClass.simpleName} at ${stackTrace.first { it.isTelemetryCode() }}"

    private fun StackTraceElement.isTelemetryCode(): Boolean = TELEMETRY_CODE.any { className.startsWith(it) }

    /** The test app with its telemetry files apart from the app's own. */
    private class WithOwnStorage(base: Context) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(baseContext.cacheDir, "off-the-main-thread").apply { mkdirs() }
    }

    private companion object {
        /** The telemetry package, and the glue that connects it to Android. */
        val TELEMETRY_CODE = listOf(
            "org.bidon.rendering.telemetry.",
            "org.bidon.rendering.RendererTelemetry",
            "org.bidon.rendering.TelemetryDirectory",
            "org.bidon.rendering.BidonRenderer",
        )
    }
}
