package org.bidon.rendering.telemetry

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.AdInfo
import org.bidon.rendering.AdType
import org.bidon.rendering.CreativeType
import org.bidon.rendering.RendererTelemetry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Telemetry as the renderer starts it samples on the session its events and its batches carry.
 *
 * The test sets the rate of `creative_rendered` to exactly this session's sampling position, which drops the event,
 * and then to the next double above it, which keeps it. A gate sampling on any other session would land on the same
 * side of both rates, so it would keep both events or drop both.
 */
@RunWith(AndroidJUnit4::class)
class GateSessionOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = File(context.cacheDir, "gate-session-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
    private val ingest = FakeIngest()
    private val restoreTrust = FakeIngest.trustInThisProcess()
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        restoreTrust()
        ingest.close()
        storage.deleteRecursively()
    }

    @Test
    fun gateUsesSharedSessionId() {
        val session = RendererTelemetry.sessionId
        val position = session.samplingPosition()
        val telemetry = RendererTelemetry.start(WithOwnStorage(context, storage))

        telemetry.applySettings(settings(JSONObject().put("url", ingest.url).put("app_key", "key"), rate = position))
        RendererTelemetry.adEvents(telemetry, CreativeType.HTML, AdInfo(DROPPED, AdType.BANNER))!!.rendered()
        telemetry.applySettings(settings(JSONObject(), rate = Math.nextUp(position)))
        RendererTelemetry.adEvents(telemetry, CreativeType.HTML, AdInfo(KEPT, AdType.BANNER))!!.rendered()

        val batch = ingest.awaitBatch(whileWaiting = telemetry::sync) { batch -> batch.events().any { it.auctionId == KEPT } }
        assertEquals(session.value, batch.getJSONObject("session").getString("id"))
        val kept = batch.events().single { it.auctionId == KEPT }.getJSONObject("envelope")
        assertEquals(session.value, kept.getString("session_id"))
        assertEquals(Math.nextUp(position), kept.getDouble("sampling_rate"), 0.0)
        // The dropped event was emitted first, so had it been kept it would be in this batch or an earlier one.
        assertTrue(
            "an event at a rate equal to the session's position was kept",
            ingest.batches().none { it.events().any { event -> event.auctionId == DROPPED } },
        )
    }

    /** [block] with `creative_rendered` sampled at [rate]. */
    private fun settings(block: JSONObject, rate: Double): TelemetrySettings =
        TelemetryBlock.parse(
            block.put("event_rates", JSONObject().put("creative_rendered", rate)),
            context.packageName,
            "1.0.0",
            fallbackAppKey = null,
        )

    private val JSONObject.auctionId: String get() = getJSONObject("envelope").optString("auction_id")

    /** The test app with its telemetry files in [storage], apart from any other telemetry in this process. */
    private class WithOwnStorage(base: Context, private val storage: File) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = storage
    }

    private companion object {
        const val DROPPED = "at-the-position"
        const val KEPT = "just-above-the-position"
    }
}
