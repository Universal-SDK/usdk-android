package org.bidon.bidon

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.AdInfo
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.AdType
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.rendering.Integration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
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
 * configures telemetry in this test APK, so the first test starts with no endpoint at all. Another class may start
 * the renderer first; that holds as long as the app is installed fresh, as a Gradle device run does. The test APK's manifest
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

    companion object {
        /** The wait between two events whose uptimes are compared. */
        private const val WAIT_MILLIS = 300L

        /** Settings a run installed from an IDE left behind would otherwise reach the first test before its own. */
        @JvmStatic
        @BeforeClass
        fun forgetStoredSettings() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(File(File(context.noBackupFilesDir, "bidon_telemetry"), Application.getProcessName()), "telemetry_config")
                .delete()
        }
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

    /**
     * The creative type the DSP declared in the bid's `rendering` reaches the event of a WebView death through
     * `createRenderer`, as the adapter hands it over: a playable drawn by the HTML renderer, whose process crashes.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    fun theDeclaredCreativeTypeOfADyingWebViewReachesTheBatch() {
        val ingest = ingest()
        initAdapter(section(ingest.url, appKey = "declared-type-key"))
        val auctionId = auctionId("declared-type")

        crashRenderProcess(auctionId, AdRenderingConfig.fromJson(JSONObject("""{ "creative": { "type": "playable" } }""")))

        val batch = ingest.awaitBatchWith(auctionId, whileWaiting = ::goToBackground)
        val events = batch.getJSONArray("events")
        val crashed = List(events.length()) { events.getJSONObject(it) }.single {
            val envelope = it.getJSONObject("envelope")
            envelope.optString("auction_id") == auctionId && envelope.getString("event_name") == "render_crashed"
        }
        assertEquals("playable", crashed.getString("crtype"))
        assertEquals("html", crashed.getString("renderer"))
    }

    /**
     * An event carries the integration the host named, and the milliseconds since the first `init`: two events a known
     * wait apart are at least that far apart, and neither counts from before this process started.
     */
    @Test
    fun labelsOfTheIntegrationReachTheBatch() {
        val ingest = ingest()
        initAdapter(section(ingest.url, appKey = "label-key"))
        val first = auctionId("labelled")
        val second = auctionId("labelled-later")

        failBlankCreative(first, Integration.MAX_CUSTOM)
        Thread.sleep(WAIT_MILLIS)
        failBlankCreative(second, Integration.MAX_CUSTOM)

        val earlier = ingest.awaitBatchWith(first, whileWaiting = ::goToBackground).envelopeOf(first)
        val later = ingest.awaitBatchWith(second, whileWaiting = ::goToBackground).envelopeOf(second)
        val sinceProcessStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        assertEquals("max_adapter", earlier.getString("host"))
        assertEquals("bidon", earlier.getString("auction_owner"))
        val uptimes = earlier.getLong("sdk_uptime_ms") to later.getLong("sdk_uptime_ms")
        assertTrue("$uptimes", uptimes.second - uptimes.first >= WAIT_MILLIS)
        assertTrue("$uptimes, $sinceProcessStart ms since the process started", uptimes.second <= sinceProcessStart)
    }

    /** A second `init` returns early, so the uptime of a later event still counts from the first. */
    @Test
    fun uptimeKeepsGrowingFromTheFirstInit() {
        val ingest = ingest()
        initAdapter(section(ingest.url, appKey = "uptime-key"))
        Thread.sleep(500)
        BidonRenderer.init(context)
        Thread.sleep(500)
        val auctionId = auctionId("uptime")

        failBlankCreative(auctionId)

        val envelope = ingest.awaitBatchWith(auctionId, whileWaiting = ::goToBackground).envelopeOf(auctionId)
        assertTrue(
            "the uptime restarted at the second init: ${envelope.getLong("sdk_uptime_ms")}",
            envelope.getLong("sdk_uptime_ms") >= 1_000,
        )
    }

    private fun JSONObject.envelopeOf(auctionId: String): JSONObject {
        val events = getJSONArray("events")
        return (0 until events.length()).map { events.getJSONObject(it).getJSONObject("envelope") }
            .first { it.optString("auction_id") == auctionId }
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
     * Loads a page with nothing on it through the renderer, which reports `ad_load_failed` for [auctionId]: an
     * error, so never sampled out.
     */
    private fun failBlankCreative(auctionId: String, integration: Integration? = null) {
        val failed = CountDownLatch(1)
        lateinit var renderer: AdRenderer
        instrumentation.runOnMainSync {
            renderer = BidonRenderer.createRenderer(
                CreativeType.HTML,
                ad = AdInfo(auctionId, AdType.BANNER, integration),
            )
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
     * Loads a page through a renderer from `createRenderer` with [rendering], labelled with [auctionId], then crashes
     * the process that renders its WebView, which the renderer reports as `render_crashed`.
     */
    private fun crashRenderProcess(auctionId: String, rendering: AdRenderingConfig) {
        val loaded = CountDownLatch(1)
        val failed = CountDownLatch(1)
        lateinit var renderer: AdRenderer
        instrumentation.runOnMainSync {
            renderer = BidonRenderer.createRenderer(CreativeType.HTML, rendering, AdInfo(auctionId, AdType.INTERSTITIAL))
            renderer.load("<html><body><p>ad</p></body></html>", object : AdRenderer.Listener {
                override fun onLoaded() = loaded.countDown()
                override fun onImpression() = Unit
                override fun onClick() = Unit
                override fun onClose() = Unit
                override fun onReward() = Unit
                override fun onError(error: Throwable) = failed.countDown()
            })
        }
        try {
            assertTrue("the creative did not load", loaded.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { (renderer.getView() as WebView).loadUrl("chrome://crash") }
            assertTrue("the renderer did not end the ad", failed.await(10, TimeUnit.SECONDS))
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
