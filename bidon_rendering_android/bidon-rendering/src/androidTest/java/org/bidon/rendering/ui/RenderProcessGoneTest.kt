package org.bidon.rendering.ui

import org.bidon.rendering.telemetry.AdEvents
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.html.ClickHandler
import org.bidon.rendering.html.HtmlRenderer
import org.bidon.rendering.html.HtmlWebView
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.mraid.MraidController
import org.bidon.rendering.mraid.MraidPlacementType
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.mraid.MraidWebView
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.bidon.rendering.vast.CompanionResource
import org.bidon.rendering.vast.ui.EndCardView
import org.bidon.rendering.telemetry.Creative
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.vast.VastCompanion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * When the process that renders a WebView dies, the app must live on. Android kills the whole app unless the WebView's
 * client handles the death, so each test here really kills that process, through the WebView's own `chrome://crash`
 * and `chrome://kill` pages. Had we not handled it, this test process would die with it.
 *
 * What the ad does next depends on where it is: before it is shown it has failed to load, on a banner it disappears
 * and fails, and on a fullscreen screen it closes the way a user would close it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class RenderProcessGoneTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "render-gone-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }

    @After
    fun deleteQueue() {
        directory.deleteRecursively()
    }

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val shown = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val errors = AtomicInteger()
        val closes = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() = shown.countDown()
        override fun onClick() = Unit
        override fun onClose() {
            closes.incrementAndGet()
            closed.countDown()
        }
        override fun onReward() = Unit
        override fun onError(error: Throwable) {
            errors.incrementAndGet()
            failed.countDown()
        }
    }

    private enum class Kind { HTML, MRAID }

    @Test
    fun htmlAdKilledBeforeItIsShownFailsToLoad() = adGoneBeforeItIsShownFailsToLoad(Kind.HTML, KILL)

    @Test
    fun mraidAdCrashedBeforeItIsShownFailsToLoad() = adGoneBeforeItIsShownFailsToLoad(Kind.MRAID, CRASH)

    @Test
    fun htmlBannerCrashedOnScreenIsRemovedAndFails() = bannerGoneOnScreenIsRemovedAndFails(Kind.HTML, CRASH)

    @Test
    fun mraidBannerKilledOnScreenIsRemovedAndFails() = bannerGoneOnScreenIsRemovedAndFails(Kind.MRAID, KILL)

    @Test
    fun htmlFullscreenAdCrashedOnScreenCloses() = fullscreenAdGoneOnScreenCloses(Kind.HTML, CRASH)

    @Test
    fun mraidFullscreenAdKilledOnScreenCloses() = fullscreenAdGoneOnScreenCloses(Kind.MRAID, KILL)

    @Test
    fun htmlAdClosedBeforeItsRendererDiesReportsNothingMore() =
        adClosedBeforeItsRendererDiesReportsNothingMore(Kind.HTML)

    @Test
    fun mraidAdClosedBeforeItsRendererDiesReportsNothingMore() =
        adClosedBeforeItsRendererDiesReportsNothingMore(Kind.MRAID)

    /** The video's end card tells its screen that its renderer died, and whether it crashed. */
    @Test
    fun anEndCardWhoseRendererDiesIsRemovedAndReportsIt() {
        val gone = CountDownLatch(1)
        val crashed = AtomicReference<Boolean>()
        val errors = AtomicInteger()
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var endCard: EndCardView
            scenario.onActivity {
                endCard = EndCardView(it).apply {
                    setListener(object : EndCardView.Listener {
                        override fun onClose() = Unit
                        override fun onClick() = Unit
                        override fun onError(error: Throwable) = run { errors.incrementAndGet(); Unit }
                        override fun onRendererGone(didCrash: Boolean) {
                            crashed.set(didCrash)
                            gone.countDown()
                        }
                    })
                    showCompanion(VastCompanion(320, 480, CompanionResource.Html("<html><body>card</body></html>")))
                }
                it.setContentView(endCard)
            }
            val webView = waitFor("the end card to load") {
                onMain { endCard.find(WebView::class.java)?.takeIf { it.progress == 100 } }
            }

            onMain { webView.loadUrl(CRASH) }

            assertTrue("renderer death not reported", gone.await(RENDERER_GONE_SECONDS, TimeUnit.SECONDS))
            assertEquals(true, crashed.get())
            assertEquals(0, errors.get())
            assertNull(onMain { endCard.find(WebView::class.java) })
            onMain { endCard.destroy() }
        }
    }

    /**
     * A screen that starts after the ad's WebView is gone, which is where a renderer that dies while the screen is
     * starting leaves it. The ad was never on screen: it fails once and is not also closed.
     */
    @Test
    fun anHtmlScreenThatFindsNoViewFailsTheAdOnceAndDoesNotCloseIt() =
        aScreenThatFindsNoViewFailsTheAdOnceAndDoesNotCloseIt(FullscreenAdActivity::class.java)

    @Test
    fun anMraidScreenThatFindsNoViewFailsTheAdOnceAndDoesNotCloseIt() =
        aScreenThatFindsNoViewFailsTheAdOnceAndDoesNotCloseIt(MraidAdActivity::class.java)

    /**
     * A screen the system destroyed in the background and restores after the ad's WebView went away. The ad was on
     * screen before, so it closes, as an ad whose renderer dies while it is shown does, rather than failing.
     */
    @Test
    fun anHtmlScreenRestoredWithoutItsViewClosesTheShownAd() =
        aScreenRestoredWithoutItsViewClosesTheShownAd(FullscreenAdActivity::class.java)

    @Test
    fun anMraidScreenRestoredWithoutItsViewClosesTheShownAd() =
        aScreenRestoredWithoutItsViewClosesTheShownAd(MraidAdActivity::class.java)

    /** A theme or language change recreates the screen. The ad was already counted as shown, and is not again. */
    @Test
    fun anHtmlScreenRecreatedWithItsViewReportsTheImpressionOnce() =
        aScreenRecreatedWithItsViewReportsTheImpressionOnce(FullscreenAdActivity::class.java)

    @Test
    fun anMraidScreenRecreatedWithItsViewReportsTheImpressionOnce() =
        aScreenRecreatedWithItsViewReportsTheImpressionOnce(MraidAdActivity::class.java)

    /** A command the creative sent just before its WebView went away reaches neither the WebView nor the publisher. */
    @Test
    fun anMraidCloseQueuedBeforeTheControllerIsDestroyedIsDropped() = mraidCloseAroundDestroy(closeFirst = true)

    /** The same for a command that reaches the controller after it was destroyed. */
    @Test
    fun anMraidCloseAfterTheControllerIsDestroyedIsDropped() = mraidCloseAroundDestroy(closeFirst = false)

    /** The shape every WebView of ours has: a [ClickHandler] as its client. */
    @Test
    fun anyWebViewWithOurClientIsTakenOffTheScreenAndReported() {
        val gone = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var container: FrameLayout
            lateinit var webView: WebView
            scenario.onActivity {
                container = FrameLayout(it)
                it.setContentView(container)
                webView = HtmlWebView(it).apply {
                    webViewClient = ClickHandler(
                        context = it,
                        onClick = {},
                        onPageLoaded = { loaded.countDown() },
                        onWebViewGone = { gone.countDown() },
                    )
                    loadDataWithBaseURL(null, "<html><body>card</body></html>", "text/html", "UTF-8", null)
                }
                container.addView(webView)
            }
            assertTrue("not loaded", loaded.await(10, TimeUnit.SECONDS))

            onMain { webView.loadUrl(CRASH) }

            assertTrue("not reported", gone.await(RENDERER_GONE_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, onMain { container.childCount })
        }
    }

    private fun adGoneBeforeItIsShownFailsToLoad(kind: Kind, how: String) {
        val events = Events()
        val renderer = load(kind, events)

        killRenderer(renderer, how)

        assertTrue("no error", events.failed.await(RENDERER_GONE_SECONDS, TimeUnit.SECONDS))
        assertNull(onMain { renderer.getView() })
        assertProcessGone(kind, how, wasShown = false)

        // Showing it now fails the usual way for an ad that is not loaded, and starts no screen.
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            assertEquals(2, events.errors.get())
            assertTrue(adScreens().isEmpty())
        }
        onMain { renderer.destroy() }
        // Neither the error that ended the ad nor the show of a dead ad is a second event.
        queued.assertNoMore()
    }

    private fun bannerGoneOnScreenIsRemovedAndFails(kind: Kind, how: String) {
        val events = Events()
        val renderer = load(kind, events)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var container: FrameLayout
            scenario.onActivity {
                container = FrameLayout(it)
                it.setContentView(container)
                // As the mediation SDK places a banner: it takes the view and adds it to its own layout.
                container.addView(renderer.getView())
            }
            assertTrue("not shown", events.shown.await(5, TimeUnit.SECONDS))

            killRenderer(renderer, how)

            assertTrue("no error", events.failed.await(RENDERER_GONE_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, onMain { container.childCount })
        }
        onMain { renderer.destroy() }
        assertRendered(kind)
        assertProcessGone(kind, how, wasShown = true)
        queued.assertNoMore()
    }

    private fun adClosedBeforeItsRendererDiesReportsNothingMore(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            assertTrue("not shown", events.shown.await(10, TimeUnit.SECONDS))
            val screen = waitFor("the ad screen") { adScreens().firstOrNull() }
            onMain { screen.finish() }
            assertTrue("not closed", events.closed.await(10, TimeUnit.SECONDS))

            // The renderer keeps the WebView after the screen closes, so it can still die.
            killRenderer(renderer, CRASH)

            waitFor("the renderer to let the WebView go", RENDERER_GONE_SECONDS * 1000) {
                Unit.takeIf { onMain { renderer.getView() } == null }
            }
            assertEquals(1, events.closes.get())
            assertEquals(0, events.errors.get())
        }
        onMain { renderer.destroy() }
        assertRendered(kind)
        // The ad was closed: the death of the shared render process is not reported for it.
        queued.assertNoMore()
    }

    private fun aScreenThatFindsNoViewFailsTheAdOnceAndDoesNotCloseIt(screen: Class<out Activity>) {
        val failures = AtomicInteger()
        val closes = AtomicInteger()
        val key = PresentedAds.register(object : PresentedAd {
            override val contentView: View? = null
            override val closeButton = AdRenderingConfig().closeButton
            override val lockedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            override var closeRequester: (() -> Unit)? = null
            override val events: AdEvents? = null
            override fun onImpression() = Unit
            override fun onClicked() = Unit
            override fun onClosed() {
                closes.incrementAndGet()
            }
            override fun onRewarded() = Unit
            override fun onFailed(error: Throwable) {
                failures.incrementAndGet()
            }
        })
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { it.startActivity(Intent(it, screen).putExtra(PresentedAds.EXTRA_KEY, key)) }

            waitFor("the ad to fail") { Unit.takeIf { failures.get() > 0 } }
            waitFor("the screen to go") { Unit.takeIf { adScreens().isEmpty() } }
            assertEquals(1, failures.get())
            assertEquals("closed an ad that was never shown", 0, closes.get())
        }
        PresentedAds.unregister(key)
    }

    private fun aScreenRestoredWithoutItsViewClosesTheShownAd(screen: Class<out Activity>) {
        val impressions = AtomicInteger()
        val failures = AtomicInteger()
        val closes = AtomicInteger()
        var view: View? = onMain { View(context) }
        val key = PresentedAds.register(object : PresentedAd {
            override val contentView: View? get() = view
            override val closeButton = AdRenderingConfig().closeButton
            override val lockedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            override var closeRequester: (() -> Unit)? = null
            override val events: AdEvents? = null
            override fun onImpression() {
                impressions.incrementAndGet()
            }
            override fun onClicked() = Unit
            override fun onClosed() {
                closes.incrementAndGet()
            }
            override fun onRewarded() = Unit
            override fun onFailed(error: Throwable) {
                failures.incrementAndGet()
            }
        })
        val intent = Intent(context, screen).putExtra(PresentedAds.EXTRA_KEY, key)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            assertEquals(1, impressions.get())

            view = null
            // Not the scenario's recreate(), which waits for a screen that here finishes instead of resuming.
            scenario.onActivity { it.recreate() }

            waitFor("the screen to go") { Unit.takeIf { adScreens().isEmpty() } }
            assertEquals("failed an ad that was already shown", 0, failures.get())
            assertEquals(1, closes.get())
            assertNull(PresentedAds.get(key))
        }
        PresentedAds.unregister(key)
    }

    private fun aScreenRecreatedWithItsViewReportsTheImpressionOnce(screen: Class<out Activity>) {
        val impressions = AtomicInteger()
        val view = onMain { View(context) }
        val key = PresentedAds.register(object : PresentedAd {
            override val contentView: View get() = view
            override val closeButton = AdRenderingConfig().closeButton
            override val lockedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            override var closeRequester: (() -> Unit)? = null
            override val events: AdEvents? = null
            override fun onImpression() {
                impressions.incrementAndGet()
            }
            override fun onClicked() = Unit
            override fun onClosed() = Unit
            override fun onRewarded() = Unit
            override fun onFailed(error: Throwable) = Unit
        })
        val intent = Intent(context, screen).putExtra(PresentedAds.EXTRA_KEY, key)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            val first = waitFor("the ad screen") { resumedAdScreen() }
            assertEquals(1, impressions.get())

            onMain { first.recreate() }

            waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== first } }
            assertEquals("counted as shown again", 1, impressions.get())
            scenario.onActivity { it.finish() }
        }
        PresentedAds.unregister(key)
    }

    private fun mraidCloseAroundDestroy(closeFirst: Boolean) {
        val closes = AtomicInteger()
        // No client on these WebViews: they must be destroyed before any later test lets a renderer die, or Android
        // ends this process for them, exactly as it would for a host app's WebView.
        val webViews = onMain { listOf(MraidWebView(context), MraidWebView(context)) }
        try {
            val (destroyedOne, liveOne) = webViews.map {
                onMain { MraidController(context, it, MraidPlacementType.INLINE, { closes.incrementAndGet() }, {}) }
            }
            onMain {
                destroyedOne.onReady()
                liveOne.onReady()
            }
            instrumentation.waitForIdleSync()

            onMain {
                if (closeFirst) {
                    destroyedOne.close()
                    destroyedOne.destroy()
                } else {
                    destroyedOne.destroy()
                    destroyedOne.close()
                }
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, closes.get())

            // The same command without the destroy does report the close, so the test can see one.
            onMain { liveOne.close() }
            instrumentation.waitForIdleSync()
            assertEquals(1, closes.get())
        } finally {
            onMain { webViews.forEach(WebView::destroy) }
        }
    }

    private fun fullscreenAdGoneOnScreenCloses(kind: Kind, how: String) {
        val events = Events()
        val renderer = load(kind, events)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            assertTrue("not shown", events.shown.await(10, TimeUnit.SECONDS))
            waitFor("the ad screen") { adScreens().firstOrNull() }

            killRenderer(renderer, how)

            assertTrue("not closed", events.closed.await(RENDERER_GONE_SECONDS, TimeUnit.SECONDS))
            waitFor("the ad screen to go") { Unit.takeIf { adScreens().isEmpty() } }
            SystemClock.sleep(500)
            assertEquals("closed more than once", 1, events.closes.get())
            assertEquals("reported as an error after it was shown", 0, events.errors.get())
        }
        onMain { renderer.destroy() }
        assertRendered(kind)
        assertProcessGone(kind, how, wasShown = true)
        queued.assertNoMore()
    }

    private fun load(kind: Kind, events: Events): AdRenderer {
        val renderer = when (kind) {
            Kind.HTML -> HtmlRenderer(context, AdRenderingConfig(), queued.adEvents(creative(kind)))
            Kind.MRAID -> MraidRenderer(context, AdRenderingConfig(), queued.adEvents(creative(kind)))
        }
        val markup = when (kind) {
            Kind.HTML -> "<html><body><p>ad</p></body></html>"
            Kind.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
        }
        instrumentation.runOnMainSync { renderer.load(markup, events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))
        return renderer
    }

    private fun creative(kind: Kind) = when (kind) {
        Kind.HTML -> Creative.HTML
        Kind.MRAID -> Creative.MRAID
    }

    private fun assertRendered(kind: Kind) {
        val event = queued.next()
        queued.assertAdEvent(event, "creative_rendered", EventClass.DIAGNOSTIC, creative(kind))
        assertTrue("no load time", event.json.getLong("load_ms") >= 0)
    }

    private fun assertProcessGone(kind: Kind, how: String, wasShown: Boolean) {
        val event = queued.next(RENDERER_GONE_SECONDS)
        queued.assertAdEvent(event, "render_process_gone", EventClass.ERROR, creative(kind))
        assertEquals(how == CRASH, event.json.getBoolean("did_crash"))
        assertEquals(wasShown, event.json.getBoolean("was_shown"))
    }

    /** Ends the process that renders [renderer]'s WebView, the way a crash or the low-memory killer would. */
    private fun killRenderer(renderer: AdRenderer, how: String) =
        onMain { (renderer.getView() as WebView).loadUrl(how) }

    private fun resumedAdScreen(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is FullscreenAdActivity || it is MraidAdActivity }
    }

    private fun adScreens(): List<Activity> = onMain {
        Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filter { it is FullscreenAdActivity || it is MraidAdActivity }
    }

    /** The first view of [type] in this view's tree, itself included. */
    private fun <T : View> View.find(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).find(type)?.let { return it }
            }
        }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 15_000, value: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            value()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "timed out after $timeoutMs ms waiting for $what" }
            SystemClock.sleep(50)
        }
    }

    private companion object {
        /**
         * How long to wait for what follows the renderer's death. A crashing renderer first writes its crash dump; on
         * a loaded machine Android took 11.8 s to report it dead (2026-10-01), so a 10 s wait failed tests whose
         * behaviour was right.
         */
        const val RENDERER_GONE_SECONDS = 30L

        /** The renderer crashes: `didCrash()` is true. */
        const val CRASH = "chrome://crash"

        /** The renderer is killed, as the system does to reclaim memory: `didCrash()` is false. */
        const val KILL = "chrome://kill"
    }
}
