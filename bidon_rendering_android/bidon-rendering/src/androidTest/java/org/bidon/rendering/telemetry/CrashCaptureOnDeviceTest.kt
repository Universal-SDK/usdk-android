package org.bidon.rendering.telemetry

import android.content.Context
import android.os.Bundle
import android.os.Messenger
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real crash, in a real process, next to a crash reporter of the host's: the reporter still hears about every
 * crash and the process still dies, a crash in our code is in the error queue of the next process, and a crash in
 * the host's code is not.
 *
 * Each test crashes [CrashVictimService]'s process and reads the result from the next one.
 */
@RunWith(AndroidJUnit4::class)
class CrashCaptureOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun aCrashOnOneOfOurThreadsIsInTheNextProcessErrorQueue() {
        val (crashed, next) = crashAndRestart(CrashVictimService.CRASH_IN_OUR_THREAD)

        val event = JSONObject(next.getStringArrayList(CrashVictimService.ERRORS)!!.single())
        assertEquals("sdk_crashed", event.getJSONObject("envelope").getString("event_name"))
        assertFalse(event.getBoolean("crashed_on_main_thread"))
        val exception = event.getJSONArray("exceptions").getJSONObject(0)
        assertEquals("java.lang.IllegalStateException", exception.getString("class"))
        assertTrue(exception.toString(), exception.getJSONArray("frames").getString(0).contains("CrashVictimService"))
        assertFalse("the message was stored", event.toString().contains("ours"))
        assertEquals("not the crashed process's session", crashed.session, event.getJSONObject("envelope").getString("session_id"))
        assertNotEquals(next.session, event.getJSONObject("envelope").getString("session_id"))
        assertEquals("renderer-work: java.lang.IllegalStateException: ours", next.hostReport)
    }

    /**
     * The crash handler reads the uptime and the last renderer's integration of the process that crashed. The crash
     * comes after a binder round trip and an unbind, so its uptime cannot still be zero. The victim process exists
     * only inside the window the test measures, so an uptime counted from device boot would exceed it.
     */
    @Test
    fun aCrashCarriesTheUptimeAndTheIntegrationOfTheProcessThatCrashed() {
        val before = SystemClock.elapsedRealtime()
        val (_, next) = crashAndRestart(CrashVictimService.CRASH_IN_OUR_THREAD)
        val window = SystemClock.elapsedRealtime() - before

        val event = JSONObject(next.getStringArrayList(CrashVictimService.ERRORS)!!.single())
        val envelope = event.getJSONObject("envelope")
        val uptime = envelope.getLong("sdk_uptime_ms")
        assertTrue(envelope.toString(), uptime > 0)
        assertTrue("uptime $uptime ms, but the crashed process lived at most $window ms", uptime <= window)
        assertEquals("max_adapter", envelope.getString("host"))
        assertEquals("max", envelope.getString("auction_owner"))
    }

    @Test
    fun aCrashInOurCoroutineIsInTheNextProcessErrorQueue() {
        val (_, next) = crashAndRestart(CrashVictimService.CRASH_IN_OUR_COROUTINE)

        val event = JSONObject(next.getStringArrayList(CrashVictimService.ERRORS)!!.single())
        assertEquals("sdk_crashed", event.getJSONObject("envelope").getString("event_name"))
        assertTrue(event.getBoolean("crashed_on_main_thread"))
        assertTrue(next.hostReport!!.endsWith("java.lang.IllegalStateException: ours"))
    }

    @Test
    fun aCrashInAListenerCalledFromVastRendererIsInTheNextProcessErrorQueue() {
        val (crashed, next) = crashAndRestart(CrashVictimService.CRASH_IN_VAST_LISTENER)

        val event = JSONObject(next.getStringArrayList(CrashVictimService.ERRORS)!!.single())
        val envelope = event.getJSONObject("envelope")
        assertEquals("sdk_crashed", envelope.getString("event_name"))
        assertEquals(crashed.session, envelope.getString("session_id"))
        assertTrue(event.getBoolean("crashed_on_main_thread"))
        val exception = event.getJSONArray("exceptions").getJSONObject(0)
        assertEquals("java.lang.IllegalStateException", exception.getString("class"))
        val frames = (0 until exception.getJSONArray("frames").length()).map { exception.getJSONArray("frames").getString(it) }
        // The listener's frame is the host's, outside org.bidon: only the VastRenderer frame below it makes this ours.
        assertTrue(frames.toString(), frames.first().startsWith("com.example.host.ThrowingListener.onLoaded("))
        assertTrue(frames.toString(), frames.any { it.startsWith("org.bidon.rendering.vast.VastRenderer") })
        assertFalse("the message was stored", event.toString().contains("ours"))
        assertTrue(next.hostReport!!.endsWith("java.lang.IllegalStateException: ours"))
    }

    @Test
    fun aCrashInTheHostAppsCodeIsNotRecordedAndStillReachesItsReporter() {
        val (_, next) = crashAndRestart(CrashVictimService.CRASH_IN_HOST_CODE)

        assertEquals(emptyList<String>(), next.getStringArrayList(CrashVictimService.ERRORS))
        assertEquals("game-loop: java.lang.IllegalStateException: host", next.hostReport)
    }

    /**
     * Crashes the victim with [how], waits for its process to die, and reads what the next process finds. The victim
     * crashes, and the next one exits, once unbound, so every test starts with a fresh process.
     */
    private fun crashAndRestart(how: Int): Pair<Bundle, Bundle> {
        val crashed = withVictim { request(how) }
        awaitDeath(crashed.arg1)
        val next = withVictim { request(CrashVictimService.READ) }
        awaitDeath(next.arg1)
        assertNotEquals("the victim was not restarted", crashed.arg1, next.arg1)
        return crashed.data to next.data
    }

    private fun <T> withVictim(block: Messenger.() -> T): T = withService<CrashVictimService, T>(context, block)

    private val Bundle.hostReport: String? get() = getString(CrashVictimService.HOST_REPORT)

    private val Bundle.session: String? get() = getString(CrashVictimService.SESSION)
}
