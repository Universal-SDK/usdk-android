package org.bidon.rendering.ui

import android.app.Activity
import android.os.Build
import android.os.SystemClock
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
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun htmlBannerCrashedOnScreenIsRemovedAndFails() {
        val events = Events()
        val renderer = load(Kind.HTML, events)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            lateinit var container: FrameLayout
            scenario.onActivity {
                container = FrameLayout(it)
                it.setContentView(container)
                renderer.attach(container)
            }
            assertTrue("not shown", events.shown.await(5, TimeUnit.SECONDS))

            killRenderer(renderer, CRASH)

            assertTrue("no error", events.failed.await(10, TimeUnit.SECONDS))
            assertEquals(0, onMain { container.childCount })
        }
        onMain { renderer.destroy() }
    }

    @Test
    fun htmlFullscreenAdCrashedOnScreenCloses() = fullscreenAdGoneOnScreenCloses(Kind.HTML, CRASH)

    @Test
    fun mraidFullscreenAdKilledOnScreenCloses() = fullscreenAdGoneOnScreenCloses(Kind.MRAID, KILL)

    /** The shape every WebView of ours has, the video end card's included: a [ClickHandler] as its client. */
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

            assertTrue("not reported", gone.await(10, TimeUnit.SECONDS))
            assertEquals(0, onMain { container.childCount })
        }
    }

    private fun adGoneBeforeItIsShownFailsToLoad(kind: Kind, how: String) {
        val events = Events()
        val renderer = load(kind, events)

        killRenderer(renderer, how)

        assertTrue("no error", events.failed.await(10, TimeUnit.SECONDS))
        assertNull(onMain { renderer.getView() })

        // Showing it now fails the usual way for an ad that is not loaded, and starts no screen.
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            SystemClock.sleep(500)
            assertEquals(2, events.errors.get())
            assertTrue(adScreens().isEmpty())
        }
        onMain { renderer.destroy() }
    }

    private fun fullscreenAdGoneOnScreenCloses(kind: Kind, how: String) {
        val events = Events()
        val renderer = load(kind, events)
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            assertTrue("not shown", events.shown.await(10, TimeUnit.SECONDS))
            waitFor("the ad screen") { adScreens().firstOrNull() }

            killRenderer(renderer, how)

            assertTrue("not closed", events.closed.await(10, TimeUnit.SECONDS))
            waitFor("the ad screen to go") { Unit.takeIf { adScreens().isEmpty() } }
            SystemClock.sleep(500)
            assertEquals("closed more than once", 1, events.closes.get())
            assertEquals("reported as an error after it was shown", 0, events.errors.get())
        }
        onMain { renderer.destroy() }
    }

    private fun load(kind: Kind, events: Events): AdRenderer {
        val renderer = when (kind) {
            Kind.HTML -> HtmlRenderer(context, AdRenderingConfig())
            Kind.MRAID -> MraidRenderer(context, AdRenderingConfig())
        }
        val markup = when (kind) {
            Kind.HTML -> "<html><body><p>ad</p></body></html>"
            Kind.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
        }
        instrumentation.runOnMainSync { renderer.load(markup, events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))
        return renderer
    }

    /** Ends the process that renders [renderer]'s WebView, the way a crash or the low-memory killer would. */
    private fun killRenderer(renderer: AdRenderer, how: String) =
        onMain { (renderer.getView() as WebView).loadUrl(how) }

    private fun adScreens(): List<Activity> = onMain {
        Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filter { it is FullscreenAdActivity || it is MraidAdActivity }
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
        /** The renderer crashes: `didCrash()` is true. */
        const val CRASH = "chrome://crash"

        /** The renderer is killed, as the system does to reclaim memory: `didCrash()` is false. */
        const val KILL = "chrome://kill"
    }
}
