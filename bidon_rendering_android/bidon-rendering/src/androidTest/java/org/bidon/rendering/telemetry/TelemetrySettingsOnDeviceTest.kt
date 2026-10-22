package org.bidon.rendering.telemetry

import android.content.Context
import android.os.Bundle
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The telemetry settings the server sent outlive the process that received them, in real processes: the next launch
 * sends with them before any config arrives. With the manifest flag off they are not kept at all.
 *
 * Each test runs [CrashVictimService] in its `:crash_victim` process, and starts a new one where it needs a next
 * launch.
 */
@RunWith(AndroidJUnit4::class)
class TelemetrySettingsOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val ingest = FakeIngest()

    @After
    fun closeIngest() = ingest.close()

    /** Run 1 is configured, stores a crash and dies; run 2 has no config call and sends the crash as run 1 would. */
    @Test
    fun storedCrashSentOnNextStart() {
        val configured = withVictim {
            request(
                CrashVictimService.CONFIGURE_THEN_CRASH,
                data = Bundle().apply {
                    putString(CrashVictimService.URL, ingest.url)
                    putString(CrashVictimService.APP_KEY, "run-1-key")
                },
            )
        }
        awaitDeath(configured.arg1)

        val (started, batch) = withVictim {
            val started = request(
                CrashVictimService.START,
                data = Bundle().apply { putString(CrashVictimService.CERTIFICATE, FakeIngest.certificatePem) },
            )
            // Bound until the batch arrives, so the process lives long enough to send it.
            started to ingest.awaitBatch { batch -> batch.events().any { it.eventName == "render_crashed" } }
        }
        awaitDeath(started.arg1)

        assertNotEquals("the victim was not restarted", configured.arg1, started.arg1)
        assertEquals("run-1-key", batch.getJSONObject("app").getString("key"))
        assertEquals(context.packageName, batch.getJSONObject("app").getString("bundle"))
        assertEquals(started.data.session, batch.getJSONObject("session").getString("id"))
        val crash = batch.events().single { it.eventName == "render_crashed" }
        assertEquals("not run 1's crash", configured.data.session, crash.getJSONObject("envelope").getString("session_id"))
    }

    /** With the manifest flag off, settings given before and after `init` are not stored, and no file is created. */
    @Test
    fun disabledByManifest() {
        val result = configure(flagOn = false)

        assertFalse("settings were stored", result.getBoolean(CrashVictimService.STORED))
        assertFalse("the telemetry directory was created", result.getBoolean(CrashVictimService.DIRECTORY_CREATED))
    }

    /** Without this, the test above could pass because the settings are never stored at all. */
    @Test
    fun storedWithTheFlagOn() {
        val result = configure(flagOn = true)

        assertTrue(result.getBoolean(CrashVictimService.STORED))
    }

    private fun configure(flagOn: Boolean): Bundle {
        val reply = withVictim { request(CrashVictimService.CONFIGURE, arg1 = if (flagOn) 1 else 0) }
        awaitDeath(reply.arg1)
        return reply.data
    }

    private fun <T> withVictim(block: Messenger.() -> T): T = withService<CrashVictimService, T>(context, block)

    private val Bundle.session: String? get() = getString(CrashVictimService.SESSION)
}
