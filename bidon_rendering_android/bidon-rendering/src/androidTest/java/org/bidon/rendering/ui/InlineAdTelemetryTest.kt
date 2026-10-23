package org.bidon.rendering.ui

import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.html.HtmlRenderer
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.telemetry.Creative
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.waitUntil
import org.bidon.rendering.whileTheDeviceIsRotated
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The events of HTML and MRAID ads as the host uses them: a banner the host takes with `getView()` and places in its
 * own layout, and a creative whose own load fails.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class InlineAdTelemetryTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "inline-ad-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }

    @After
    fun deleteQueue() {
        directory.deleteRecursively()
    }

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val impressions = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() = run { impressions.incrementAndGet(); Unit }
        override fun onClick() = Unit
        override fun onClose() = closed.countDown()
        override fun onReward() = Unit
        override fun onError(error: Throwable) = failed.countDown()
    }

    private enum class Kind(val creative: Creative) { HTML(Creative.HTML), MRAID(Creative.MRAID) }

    @Test
    fun anHtmlBannerPlacedByTheHostIsRenderedOnce() = aBannerPlacedByTheHostIsRenderedOnce(Kind.HTML)

    @Test
    fun anMraidBannerPlacedByTheHostIsRenderedOnce() = aBannerPlacedByTheHostIsRenderedOnce(Kind.MRAID)

    /** A host that places the banner through the renderer's own `attach` gets the same single rendering. */
    @Test
    fun anHtmlBannerAttachedThroughTheRendererIsRenderedOnce() {
        val events = Events()
        val renderer = renderer(Kind.HTML)
        load(renderer, Kind.HTML, events)
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var container: FrameLayout
            scenario.onActivity {
                container = FrameLayout(it)
                it.setContentView(container)
                renderer.attach(container)
            }
            assertCountedWithDuration(queued.next())
            scenario.onActivity { renderer.attach(container) }
            instrumentation.waitForIdleSync()

            assertEquals(1, onMain { container.childCount })
            assertEquals(1, events.impressions.get())
            queued.assertNoMore()
        }
        instrumentation.runOnMainSync { renderer.destroy() }
    }

    /** An MRAID banner its creative closed is over: the later death of the shared render process is not its event. */
    @Test
    fun anMraidBannerItsCreativeClosedReportsNoDeath() {
        val events = Events()
        val renderer = renderer(Kind.MRAID)
        load(renderer, Kind.MRAID, events)
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContentView(FrameLayout(it).apply { addView(renderer.getView()) }) }
            queued.assertImpression(queued.next())

            onMain { (renderer.getView() as WebView).evaluateJavascript("mraid.close()", null) }
            assertTrue("the creative's close was not reported", events.closed.await(5, TimeUnit.SECONDS))
            onMain { (renderer.getView() as WebView).loadUrl("chrome://crash") }

            assertTrue("the death was not handled", events.failed.await(30, TimeUnit.SECONDS))
            queued.assertNoMore()
        }
        instrumentation.runOnMainSync { renderer.destroy() }
    }

    @Test
    fun anHtmlBannerReAddedAfterTheHostWasRecreatedIsNotCountedAgain() =
        aBannerReAddedToANewHostScreenIsNotCountedAgain(Kind.HTML, HostGoesAway.RECREATED)

    @Test
    fun anMraidBannerReAddedAfterTheHostWasRecreatedIsNotCountedAgain() =
        aBannerReAddedToANewHostScreenIsNotCountedAgain(Kind.MRAID, HostGoesAway.RECREATED)

    @Test
    fun anHtmlBannerOnAHostTheSystemRotatedIsNotCountedAgain() =
        aBannerReAddedToANewHostScreenIsNotCountedAgain(Kind.HTML, HostGoesAway.ROTATED)

    @Test
    fun anMraidBannerOnAHostTheSystemRotatedIsNotCountedAgain() =
        aBannerReAddedToANewHostScreenIsNotCountedAgain(Kind.MRAID, HostGoesAway.ROTATED)

    @Test
    fun anHtmlCreativeWhoseOwnLoadFailsIsReportedWithTheWebViewCode() = aCreativeWhoseOwnLoadFails(Kind.HTML)

    @Test
    fun anMraidCreativeWhoseOwnLoadFailsIsReportedWithTheWebViewCode() = aCreativeWhoseOwnLoadFails(Kind.MRAID)

    @Test
    fun anHtmlCreativeWhoseOwnLoadFailsOnAnUnsupportedSchemeIsTheCreativesFailure() =
        aCreativeWhoseOwnLoadFailsOnAnUnsupportedScheme(Kind.HTML)

    @Test
    fun anMraidCreativeWhoseOwnLoadFailsOnAnUnsupportedSchemeIsTheCreativesFailure() =
        aCreativeWhoseOwnLoadFailsOnAnUnsupportedScheme(Kind.MRAID)

    @Test
    fun anHtmlNavigationThatFailsAfterTheLoadIsNotALoadFailure() = aNavigationAfterTheLoadFails(Kind.HTML)

    /** A two-part expand loads its URL into the same WebView after the creative loaded. */
    @Test
    fun anMraidNavigationThatFailsAfterTheLoadIsNotALoadFailure() = aNavigationAfterTheLoadFails(Kind.MRAID)

    private fun aBannerPlacedByTheHostIsRenderedOnce(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, kind, events)
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var container: FrameLayout
            scenario.onActivity {
                container = FrameLayout(it)
                it.setContentView(container)
                container.addView(renderer.getView())
            }
            assertCountedWithDuration(queued.next())

            // The host moves the banner, for example on a refresh of its own layout.
            scenario.onActivity {
                container.removeView(renderer.getView())
                container.addView(renderer.getView())
            }
            instrumentation.waitForIdleSync()

            assertEquals(1, events.impressions.get())
            queued.assertNoMore()
        }
        instrumentation.runOnMainSync { renderer.destroy() }
    }

    /** How the host's screen goes away before the host puts the banner it kept on a new one. */
    private enum class HostGoesAway {
        /** Recreated by the app, for example on a theme change. */
        RECREATED,

        /** Recreated by the system, which turned the device. */
        ROTATED,
    }

    /** The banner was seen once, so it is counted once, however the host's screen was replaced. */
    private fun aBannerReAddedToANewHostScreenIsNotCountedAgain(kind: Kind, hostGoesAway: HostGoesAway) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, kind, events)
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            val banner = onMain { renderer.getView()!! }
            scenario.onActivity { it.setContentView(FrameLayout(it).apply { addView(banner) }) }
            assertCountedWithDuration(queued.next())

            val reAddAndCheck = {
                scenario.onActivity { activity ->
                    (banner.parent as? ViewGroup)?.removeView(banner)
                    activity.setContentView(FrameLayout(activity).apply { addView(banner) })
                }
                instrumentation.waitForIdleSync()

                assertTrue("the banner is not on the new screen", onMain { banner.isAttachedToWindow })
                assertEquals(1, events.impressions.get())
                queued.assertNoMore()
            }
            when (hostGoesAway) {
                HostGoesAway.RECREATED -> {
                    scenario.recreate()
                    reAddAndCheck()
                }
                HostGoesAway.ROTATED -> instrumentation.whileTheDeviceIsRotated {
                    instrumentation.waitUntil("the host was not recreated") { !banner.isAttachedToWindow }
                    reAddAndCheck()
                }
            }
        }
        instrumentation.runOnMainSync { renderer.destroy() }
    }

    /** The impression of a banner the host asked for, so it is measured from that request. */
    private fun assertCountedWithDuration(event: QueuedEvents.Stored) {
        queued.assertImpression(event)
        queued.assertRenderDuration(event)
    }

    private fun aCreativeWhoseOwnLoadFails(kind: Kind) {
        val (failed, error) = mainFrameFailure(kind, UNREACHABLE)
        // The host does not resolve, which is a network failure and not the creative's.
        assertEquals("8003", failed.json.getString("error_code"))
        assertEquals("network", failed.json.getString("error_domain"))
        assertEquals("webview_load", failed.json.getString("failed_stage"))
        assertEquals("webview", failed.json.getString("external_error_source"))
        assertEquals("not the code the WebView reported", error.errorCode.toString(), failed.json.getString("external_error_code"))
        // The WebView's own code, not a placeholder for a missing one.
        assertNotEquals(WebViewClient.ERROR_UNKNOWN.toString(), failed.json.getString("external_error_code"))
        assertTrue("no load time", failed.json.getLong("duration_ms") >= 0)
    }

    private fun aCreativeWhoseOwnLoadFailsOnAnUnsupportedScheme(kind: Kind) {
        val (failed, error) = mainFrameFailure(kind, UNSUPPORTED_SCHEME)

        // A scheme the WebView cannot load is the creative's failure, not the network's.
        assertEquals(WebViewClient.ERROR_UNSUPPORTED_SCHEME, error.errorCode)
        assertEquals("6001", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("webview_load", failed.json.getString("failed_stage"))
        assertEquals("webview", failed.json.getString("external_error_source"))
        assertEquals("not the code the WebView reported", error.errorCode.toString(), failed.json.getString("external_error_code"))
        assertTrue("no load time", failed.json.getLong("duration_ms") >= 0)
    }

    /**
     * Loads a creative of [kind] whose main frame fails before its page finished, with the error a real WebView
     * reported for [url], and returns the one failure the renderer reported, with that error.
     */
    private fun mainFrameFailure(kind: Kind, url: String): Pair<QueuedEvents.Stored, WebResourceError> {
        val (request, error) = realMainFrameError(url)
        val events = Events()
        val renderer = renderer(kind)
        val failed = try {
            instrumentation.runOnMainSync {
                renderer.load(markup(kind), events)
                val webView = renderer.getView() as WebView
                webView.webViewClient.onReceivedError(webView, request, error)
            }
            assertTrue("no error", events.failed.await(5, TimeUnit.SECONDS))
            queued.next()
        } finally {
            instrumentation.runOnMainSync { renderer.destroy() }
        }
        queued.assertAdEvent(failed, "ad_load_failed", EventClass.ERROR, null)
        queued.assertNoMore()
        return failed to error
    }

    private fun aNavigationAfterTheLoadFails(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, kind, events)
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        instrumentation.runOnMainSync { (renderer.getView() as WebView).loadUrl(UNREACHABLE) }
        assertTrue("no error", events.failed.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync { renderer.destroy() }
        queued.assertNoMore()
    }

    /** The request and error a WebView reports for a main frame nothing answers, as the framework builds them. */
    private fun realMainFrameError(url: String): Pair<WebResourceRequest, WebResourceError> {
        val reported = CountDownLatch(1)
        var failure: Pair<WebResourceRequest, WebResourceError>? = null
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            failure = request to error
                            reported.countDown()
                        }
                    }
                }
                loadUrl(url)
            }
        }
        assertTrue("the page at $url did not fail", reported.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webView.destroy() }
        return failure!!
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun renderer(kind: Kind): AdRenderer = when (kind) {
        Kind.HTML -> HtmlRenderer(context, AdRenderingConfig(), queued.adEvents(kind.creative))
        Kind.MRAID -> MraidRenderer(context, AdRenderingConfig(), queued.adEvents(kind.creative))
    }

    private fun load(renderer: AdRenderer, kind: Kind, events: Events) =
        instrumentation.runOnMainSync { renderer.load(markup(kind), events) }

    private fun markup(kind: Kind) = when (kind) {
        Kind.HTML -> "<html><body><p>ad</p></body></html>"
        Kind.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
    }

    private companion object {
        /** The reserved `.invalid` domain never resolves, so the load fails on the host lookup. */
        const val UNREACHABLE = "http://creative.invalid/"
        const val UNSUPPORTED_SCHEME = "bidontest-unknown://page"
    }
}
