package org.bidon.rendering

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.telemetry.awaitDeath
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stored events leave the device when the app goes to the background, through the real path: telemetry started by
 * `RendererTelemetry` with an endpoint, Android's `TRIM_MEMORY_UI_HIDDEN` in a host process, the sync, and a request
 * over `HttpURLConnection`. The send timer is a minute, so a batch that arrives within seconds was sent because the
 * app left the foreground.
 */
@RunWith(AndroidJUnit4::class)
class TelemetrySentInBackgroundTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = LocalHttpServer().apply { serve(PATH, "application/json", """{"accepted":3}""".toByteArray()) }
    private val directory = File(context.cacheDir, "telemetry-host-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
    private var hostPid = 0

    @After
    fun tearDown() {
        if (hostPid != 0) {
            Process.killProcess(hostPid)
            awaitDeath(hostPid)
        }
        server.close()
        directory.deleteRecursively()
    }

    @Test
    fun storedEventsReachTheServerWithinSecondsOfTheAppGoingToTheBackground() {
        context.startActivity(
            Intent(context, TelemetryHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(TelemetryHostActivity.EXTRA_URL, server.url(PATH))
                .putExtra(TelemetryHostActivity.EXTRA_DIRECTORY, directory.path),
        )
        val (pid, session) = awaitFile(TelemetryHostActivity.READY_FILE, "the host never stored its events").lines()
        hostPid = pid.toInt()
        // Let the host's screen finish appearing, so leaving it is a real transition to the background.
        Thread.sleep(1_000)
        assertEquals("sent before the app left the foreground", 0, server.requestCount(PATH))

        val leftAt = SystemClock.elapsedRealtime()
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val body = awaitBody()
        Log.i(TAG, "Batch received ${SystemClock.elapsedRealtime() - leftAt} ms after the app left the foreground")

        val json = JSONObject(body)
        assertEquals("app_key", json.getJSONObject("app").getString("key"))
        assertEquals(context.packageName, json.getJSONObject("app").getString("bundle"))
        assertEquals(session, json.getJSONObject("session").getString("id"))
        val events = json.getJSONArray("events")
        assertEquals(
            listOf("event_0", "event_1", "event_2", "ready"),
            List(events.length()) { events.getJSONObject(it).getJSONObject("envelope").getString("event_name") },
        )
    }

    private fun awaitFile(name: String, failure: String): String {
        val file = File(directory, name)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.exists()) file.readText().takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError(failure)
    }

    /** The first batch, within the few seconds Android may leave a cached app before freezing it. */
    private fun awaitBody(): String {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            server.bodiesPostedTo(PATH).firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no batch within 5 s of the app going to the background; see logcat BidonTelemetry")
    }

    private companion object {
        const val PATH = "/v2/telemetry"
        const val TAG = "TelemetrySentInBgTest"
    }
}
