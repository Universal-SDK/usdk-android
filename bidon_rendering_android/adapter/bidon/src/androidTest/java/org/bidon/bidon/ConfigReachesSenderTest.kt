package org.bidon.bidon

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.AdInfo
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdType
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The `telemetry` block of the adapter's config section reaches the renderer's sender: after the adapter's `init`, the
 * renderer's events arrive at the block's URL, in batches that carry the block's app key and the host's package name.
 *
 * The renderer is a process singleton, started once and never reset, so these tests share one telemetry and run in
 * name order. The host starts the renderer before the adapter in every test, as the demo app does. Only this class
 * configures telemetry in this test APK, so the first test starts with no endpoint at all. The test APK's manifest
 * switches telemetry on.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ConfigReachesSenderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val directory = File(File(context.noBackupFilesDir, "bidon_telemetry"), Application.getProcessName())
    private val ingests = mutableListOf<FakeIngest>()
    private lateinit var restoreTrust: () -> Unit

    @Before
    fun setUp() {
        restoreTrust = FakeIngest.trustInThisProcess()
        BidonRenderer.init(context)
    }

    @After
    fun tearDown() {
        ingests.forEach { it.close() }
        restoreTrust()
        context.getSharedPreferences(AppKeyStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Events stored while telemetry had no endpoint are sent as soon as the adapter delivers one. */
    @Test
    fun endpointAfterStart() {
        assertFalse(
            "an endpoint was configured before this test; it must run first and be the only test that configures one",
            File(directory, "telemetry_config").exists(),
        )
        val ingest = ingest()
        val auctionId = auctionId("stored-before-config")
        val errorsBefore = errorsFileLength()

        failBlankCreative(auctionId)
        awaitStored(errorsBefore)
        initAdapter(section(ingest.url, appKey = "key-after-start"))

        // No sync: the new endpoint's sender sends what the store already holds.
        val batch = ingest.awaitBatchWith(auctionId)
        assertEquals("key-after-start", batch.getJSONObject("app").getString("key"))
        assertEquals(context.packageName, batch.getJSONObject("app").getString("bundle"))
    }

    /** The host called `init` first, without config; the block the adapter delivers later still decides the URL. */
    @Test
    fun hostInitFirst() {
        val earlier = ingest()
        initAdapter(section(earlier.url, appKey = "earlier-key"))
        val ingest = ingest()
        initAdapter(section(ingest.url, appKey = "host-first-key"))
        val auctionId = auctionId("after-config")

        failBlankCreative(auctionId)

        val batch = ingest.awaitBatchWith(auctionId, whileWaiting = ::goToBackground)
        assertEquals("host-first-key", batch.getJSONObject("app").getString("key"))
        assertEquals(context.packageName, batch.getJSONObject("app").getString("bundle"))
        assertTrue("the event went to the replaced URL", earlier.batches().none { auctionId in it.auctionIds() })
    }

    /** A block without `app_key` is sent as the app key the mediation SDK stored at `initialize()`. */
    @Test
    fun storedAppKeyReachesTheBatch() {
        context.getSharedPreferences(AppKeyStore.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(AppKeyStore.APP_KEY, "key-from-preferences")
            .commit()
        val ingest = ingest()
        initAdapter(section(ingest.url, appKey = null))
        val auctionId = auctionId("stored-app-key")

        failBlankCreative(auctionId)

        val batch = ingest.awaitBatchWith(auctionId, whileWaiting = ::goToBackground)
        assertEquals("key-from-preferences", batch.getJSONObject("app").getString("key"))
    }

    private fun ingest() = FakeIngest().also { ingests += it }

    private fun auctionId(label: String) = "$label-${System.nanoTime()}"

    /** The adapter's section of `/v2/config`, with a `telemetry` block that sends to [url] as [appKey]. */
    private fun section(url: String, appKey: String?): String {
        val telemetry = JSONObject().put("url", url).put("sample_rate", 1.0)
        if (appKey != null) telemetry.put("app_key", appKey)
        return JSONObject().put("sdk_instance_id", "instance").put("telemetry", telemetry).toString()
    }

    private fun initAdapter(section: String) = runBlocking {
        val adapter = BidonAdapter()
        adapter.init(context, adapter.parseConfigParam(section))
    }

    /**
     * Loads a page with nothing on it through the renderer, which reports `creative_load_failed` for [auctionId]: an
     * error, so never sampled out.
     */
    private fun failBlankCreative(auctionId: String) {
        val failed = CountDownLatch(1)
        lateinit var renderer: AdRenderer
        instrumentation.runOnMainSync {
            renderer = BidonRenderer.createRenderer(CreativeType.HTML, ad = AdInfo(auctionId, AdType.BANNER))
            renderer.load("<html><body></body></html>", object : AdRenderer.Listener {
                override fun onLoaded() = Unit
                override fun onImpression() = Unit
                override fun onClick() = Unit
                override fun onClose() = Unit
                override fun onReward() = Unit
                override fun onError(error: Throwable) = failed.countDown()
            })
        }
        try {
            assertTrue("the blank creative did not fail", failed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { renderer.destroy() }
        }
    }

    /**
     * What Android tells an app whose UI was hidden. The test process runs under instrumentation and never hears it
     * on its own; telemetry syncs and sends on it.
     */
    private fun goToBackground() = instrumentation.runOnMainSync {
        (context.applicationContext as Application).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    /** The length of the error queue's file, once the telemetry thread has opened it. */
    private fun errorsFileLength(): Long {
        val errors = File(directory, "errors")
        val deadline = System.currentTimeMillis() + 5_000
        while (!errors.exists()) {
            check(System.currentTimeMillis() < deadline) { "the telemetry store never opened" }
            Thread.sleep(20)
        }
        return errors.length()
    }

    /** Waits until the telemetry thread has written an error event past [lengthBefore]. */
    private fun awaitStored(lengthBefore: Long) {
        val deadline = System.currentTimeMillis() + 5_000
        while (File(directory, "errors").length() <= lengthBefore) {
            check(System.currentTimeMillis() < deadline) { "the event was never stored" }
            Thread.sleep(20)
        }
    }
}
