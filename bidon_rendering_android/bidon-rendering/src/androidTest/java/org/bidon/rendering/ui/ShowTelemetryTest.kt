package org.bidon.rendering.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.LocalHttpServer
import org.bidon.rendering.html.HtmlRenderer
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.bidon.rendering.telemetry.ErrorCode
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.ShowStage
import org.bidon.rendering.vast.VastRenderer
import org.bidon.rendering.vast.ui.VideoAdActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The events of a full screen show on a real device: every `showFullscreen()` call emits `ad_show_requested`, and a
 * call that cannot end in an impression ends in one `ad_show_failed` with the code, owner and stage of its site, one
 * `onError` for the host, and no screen that should not be there. Each case reads the telemetry queue as production
 * fills it.
 *
 * 7003, 7005 and 7002 are checked where their sites are driven: `RenderProcessGoneTest` and `VideoAdTrackingTest`.
 * 11003 has no site in the renderer yet, so it has no test here.
 */
@RunWith(AndroidJUnit4::class)
class ShowTelemetryTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = LocalHttpServer()
    private val directory = File(context.cacheDir, "show-telemetry-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }

    @After
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val shown = CountDownLatch(1)
        val errors = AtomicInteger()
        val closes = AtomicInteger()
        val impressions = AtomicInteger()
        val lastError = AtomicReference<Throwable?>()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() {
            impressions.incrementAndGet()
            shown.countDown()
        }
        override fun onClick() = Unit
        override fun onClose() {
            closes.incrementAndGet()
        }
        override fun onReward() = Unit
        override fun onError(error: Throwable) {
            lastError.set(error)
            errors.incrementAndGet()
        }
    }

    private enum class Kind(val renderer: Renderer) {
        HTML(Renderer.HTML),
        MRAID(Renderer.MRAID),
        VAST(Renderer.VAST),
    }

    // time_since_loaded_ms

    @Test
    fun anHtmlAdShownAfterAWaitReportsTheWaitSinceItLoaded() = aShowReportsTheWaitSinceTheAdLoaded(Kind.HTML)

    @Test
    fun anMraidAdShownAfterAWaitReportsTheWaitSinceItLoaded() = aShowReportsTheWaitSinceTheAdLoaded(Kind.MRAID)

    @Test
    fun aVastAdShownAfterAWaitReportsTheWaitSinceItLoaded() = aShowReportsTheWaitSinceTheAdLoaded(Kind.VAST)

    /** The load ends when the renderer reports it loaded, so a known wait after that is the least the field can say. */
    private fun aShowReportsTheWaitSinceTheAdLoaded(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)
        SystemClock.sleep(WAIT_BEFORE_SHOW_MS)

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { renderer.showFullscreen(it) }
                assertTrue("not shown", events.shown.await(15, TimeUnit.SECONDS))
            } finally {
                val screens = adScreens()
                onMain { screens.forEach(Activity::finish) }
                waitFor("the ad screen to go") { Unit.takeIf { adScreens().isEmpty() } }
                onMain { renderer.destroy() }
            }
        }

        val requested = queued.next()
        queued.assertShowRequested(requested)
        val sinceLoaded = requested.json.getLong("time_since_loaded_ms")
        assertTrue("$sinceLoaded ms is less than the wait", sinceLoaded >= WAIT_BEFORE_SHOW_MS)
        assertTrue("$sinceLoaded ms is more than the test took", sinceLoaded < MAX_SINCE_LOADED_MS)
    }

    // 7001

    /** The only media file is one the device cannot play: the ad loads, and its show fails at the media choice. */
    @Test
    fun aVastWhoseOnlyMediaIsFlvFailsTheShowWith7001() {
        val events = Events()
        val renderer = newRenderer(Kind.VAST)
        instrumentation.runOnMainSync { renderer.load(vast("video/x-flv", "/video.flv"), events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }

            assertEquals(1, events.errors.get())
            assertTrue("a screen started", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(
            queued.next(),
            ErrorCode.NO_PLAYABLE_MEDIA,
            external = "403" to "vast",
            exceptionClass = "java.lang.IllegalStateException",
        )
        queued.assertNoMore()
        assertOneErrorPerShowFailure(events, showFailures = 1)
    }

    // 7004

    @Test
    fun anHtmlScreenThatCannotStartFailsTheShowWith7004() = aScreenThatCannotStartFailsTheShowWith7004(Kind.HTML)

    @Test
    fun anMraidScreenThatCannotStartFailsTheShowWith7004() = aScreenThatCannotStartFailsTheShowWith7004(Kind.MRAID)

    @Test
    fun aVideoScreenThatCannotStartFailsTheShowWith7004() = aScreenThatCannotStartFailsTheShowWith7004(Kind.VAST)

    /**
     * The host's screen throws [ActivityNotFoundException] when asked to start the ad screen. Nothing reaches the
     * caller of `showFullscreen()`; the host hears `onError` once.
     */
    private fun aScreenThatCannotStartFailsTheShowWith7004(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)

        ActivityScenario.launch(RefusingActivity::class.java).use { scenario ->
            var thrown: Throwable? = null
            scenario.onActivity { thrown = runCatching { renderer.showFullscreen(it) }.exceptionOrNull() }

            assertEquals("showFullscreen threw", null, thrown)
            assertEquals(1, events.errors.get())
            assertTrue("a screen started", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(
            queued.next(),
            ErrorCode.AD_SCREEN_NOT_STARTED,
            exceptionClass = ActivityNotFoundException::class.java.name,
        )
        // The destroy that follows is not another failure, and the ad never had an impression to count.
        queued.assertNoMore()
        assertEquals(0, events.impressions.get())
        assertEquals(0, events.closes.get())
        assertOneErrorPerShowFailure(events, showFailures = 1)
    }

    // 11001

    /**
     * The page is still loading (a script that never answers holds it), so the ad is not loaded: the show fails at
     * the ready check as the host's mistake, and no screen opens on a half-loaded page.
     */
    @Test
    fun aShowWhileAnHtmlLoadIsRunningFailsWith11001AndOpensNoScreen() {
        server.stall("/hold.js")
        val events = Events()
        val renderer = newRenderer(Kind.HTML)
        instrumentation.runOnMainSync {
            renderer.load("<html><body><script src=\"${server.url("/hold.js")}\"></script></body></html>", events)
        }
        waitFor("the page to start loading") { (server.requestCount("/hold.js") == 1).takeIf { it } }

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }

            assertEquals(1, events.errors.get())
            assertEquals("The ad cannot be shown: SHOW_NOT_READY (11001)", events.lastError.get()?.message)
            SystemClock.sleep(500)
            assertTrue("a screen started", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertShowRequested(queued.next(), afterLoad = false)
        queued.assertShowFailed(queued.next(), ErrorCode.SHOW_NOT_READY)
        // The destroy cancels the load, which is not a load failure, and closes nothing.
        queued.assertNoMore()
        assertEquals(0, events.closes.get())
        assertOneErrorPerShowFailure(events, showFailures = 1)
    }

    /**
     * A show refused at the ready check is not a show: the load that was still running and then fails is reported as
     * a load failure. The held script is cut off when the server closes, and the page it leaves is blank.
     */
    @Test
    fun aShowRefusedDuringAnHtmlLoadDoesNotHideTheLoadFailureThatFollows() {
        server.stall("/hold.js")
        val events = Events()
        val renderer = newRenderer(Kind.HTML)
        instrumentation.runOnMainSync {
            renderer.load("<html><body><script src=\"${server.url("/hold.js")}\"></script></body></html>", events)
        }
        waitFor("the page to start loading") { (server.requestCount("/hold.js") == 1).takeIf { it } }

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            assertEquals(1, events.errors.get())

            server.close()

            waitFor("the load to fail") { (events.errors.get() == 2).takeIf { it } }
            assertTrue("a screen started", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertShowRequested(queued.next(), afterLoad = false)
        queued.assertShowFailed(queued.next(), ErrorCode.SHOW_NOT_READY)
        val failed = queued.next()
        queued.assertAdEvent(failed, "ad_load_failed", EventClass.ERROR, renderer = null)
        assertEquals(ErrorCode.BLANK_CREATIVE.number.toString(), failed.json.getString("error_code"))
        queued.assertNoMore()
        assertEquals("the ad loaded", 1L, events.loaded.count)
        assertOneErrorPerShowOrLoadFailure(events, showFailures = 1, loadFailures = 1)
    }

    @Test
    fun anHtmlShownAfterDestroyFailsWith11001AndCallsNoOne() = aShowAfterDestroyFailsWith11001AndCallsNoOne(Kind.HTML)

    @Test
    fun anMraidShownAfterDestroyFailsWith11001AndCallsNoOne() = aShowAfterDestroyFailsWith11001AndCallsNoOne(Kind.MRAID)

    @Test
    fun aVastShownAfterDestroyFailsWith11001AndCallsNoOne() = aShowAfterDestroyFailsWith11001AndCallsNoOne(Kind.VAST)

    /** After `destroy()` the listener is cleared: the events still go out, and there is nobody to call back. */
    private fun aShowAfterDestroyFailsWith11001AndCallsNoOne(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)
        onMain { renderer.destroy() }

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }

            SystemClock.sleep(500)
            assertTrue("a screen started", adScreens().isEmpty())
        }

        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(queued.next(), ErrorCode.SHOW_NOT_READY)
        queued.assertNoMore()
        assertEquals("a callback after destroy", 0, events.errors.get())
        assertEquals(0, events.closes.get())
    }

    // 11002

    @Test
    fun aSecondShowOfAnHtmlAdFailsWith11002AndOpensNoSecondScreen() = aSecondShowFailsWith11002(Kind.HTML)

    @Test
    fun aSecondShowOfAnMraidAdFailsWith11002AndOpensNoSecondScreen() = aSecondShowFailsWith11002(Kind.MRAID)

    @Test
    fun aSecondShowOfAVideoAdFailsWith11002AndOpensNoSecondScreen() = aSecondShowFailsWith11002(Kind.VAST)

    private fun aSecondShowFailsWith11002(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { renderer.showFullscreen(it) }
                assertTrue("not shown", events.shown.await(15, TimeUnit.SECONDS))
                val screen = waitFor("the ad screen") { adScreens().firstOrNull() }

                scenario.onActivity { renderer.showFullscreen(it) }

                assertEquals(1, events.errors.get())
                SystemClock.sleep(500)
                assertEquals("a second screen opened", listOf(screen), adScreens())
                assertEquals(1, events.impressions.get())
                assertEquals(0, events.closes.get())
            } finally {
                val screens = adScreens()
                onMain { screens.forEach(Activity::finish) }
                waitFor("the ad screen to go") { Unit.takeIf { adScreens().isEmpty() } }
                onMain { renderer.destroy() }
            }
        }

        queued.assertShowRequested(queued.next())
        queued.assertImpression(queued.next())
        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(queued.next(), ErrorCode.SHOW_TWICE)
        // The screen of the first show closing after its impression is not a failure.
        queued.assertNoMore()
        assertOneErrorPerShowFailure(events, showFailures = 1)
    }

    @Test
    fun anHtmlAdShownInlineFailsAFullscreenShowWith11002() = anAdShownInlineFailsAFullscreenShowWith11002(Kind.HTML)

    @Test
    fun anMraidAdShownInlineFailsAFullscreenShowWith11002() = anAdShownInlineFailsAFullscreenShowWith11002(Kind.MRAID)

    /**
     * The host put the ad's view on its own screen, where it had its impression, and then asks for it on a full
     * screen: the ad was shown already, so no screen takes its view away from the host.
     */
    private fun anAdShownInlineFailsAFullscreenShowWith11002(kind: Kind) {
        val events = Events()
        val renderer = load(kind, events)

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContentView(FrameLayout(it).also(renderer::attach)) }
            assertTrue("not shown inline", events.shown.await(15, TimeUnit.SECONDS))

            scenario.onActivity { renderer.showFullscreen(it) }

            assertEquals(1, events.errors.get())
            SystemClock.sleep(500)
            assertTrue("a screen started", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertImpression(queued.next())
        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(queued.next(), ErrorCode.SHOW_TWICE)
        queued.assertNoMore()
        assertEquals(1, events.impressions.get())
        assertOneErrorPerShowFailure(events, showFailures = 1)
    }

    // 7006

    /**
     * The creative closes itself after the show was asked for and before its screen started. The screen closes as soon
     * as it starts: the show ends once, closed before its impression, and the host hears one close and no impression.
     */
    @Test
    fun anMraidAdThatClosesItselfBeforeItsScreenStartsClosesOnceWith7006() {
        val events = Events()
        val renderer = load(Kind.MRAID, events)

        ActivityScenario.launch(HoldingActivity::class.java).use { scenario ->
            var held: Intent? = null
            scenario.onActivity {
                renderer.showFullscreen(it)
                held = it.held
            }
            onMain { (renderer.getView() as WebView).evaluateJavascript("mraid.close()", null) }
            // The bridge hands the close to the main thread; nothing may close before the screen does.
            SystemClock.sleep(1_000)
            assertEquals("closed before the screen started", 0, events.closes.get())

            context.startActivity(held!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            waitFor("the ad to close") { (events.closes.get() == 1).takeIf { it } }
            SystemClock.sleep(500)
            assertTrue("a screen stayed", adScreens().isEmpty())
        }
        onMain { renderer.destroy() }

        queued.assertShowRequested(queued.next())
        queued.assertShowFailed(queued.next(), ErrorCode.CLOSED_BEFORE_IMPRESSION, stage = ShowStage.VIEW_ATTACH)
        queued.assertNoMore()
        assertEquals(1, events.closes.get())
        assertEquals(0, events.impressions.get())
        assertOneErrorPerShowFailure(events, showFailures = 0)
    }

    /**
     * The video never starts (its media is held), and its screen is recreated, as a configuration change does, and then
     * closed. The recreation is not a close; the close ends the show as closed before its impression, at playback.
     */
    @Test
    fun aVideoScreenRecreatedAndThenClosedBeforeItsFirstFrameEndsTheShowOnceWith7006() {
        server.stall("/video.mp4")
        val events = Events()
        val renderer = newRenderer(Kind.VAST)
        instrumentation.runOnMainSync { renderer.load(vast("video/mp4", "/video.mp4"), events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity { renderer.showFullscreen(it) }
            val screen = waitFor("the video screen") { resumedAdScreen() }
            queued.assertShowRequested(queued.next())

            onMain { screen.recreate() }

            val recreated = waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== screen } }
            queued.assertNoMore(500)
            assertEquals("a recreation closed the ad", 0, events.closes.get())

            onMain { recreated.finish() }

            waitFor("the ad to close") { (events.closes.get() == 1).takeIf { it } }
        }
        onMain { renderer.destroy() }

        queued.assertShowFailed(queued.next(), ErrorCode.CLOSED_BEFORE_IMPRESSION, stage = ShowStage.PLAYBACK_START)
        queued.assertNoMore()
        assertEquals(0, events.impressions.get())
        assertOneErrorPerShowFailure(events, showFailures = 0)
    }

    // AC 9: the host hears of every failure, and of nothing else

    /** A show that works emits no failure and calls no `onError`. */
    @Test
    fun aShowThatEndsInAnImpressionEmitsNoFailureAndCallsNoOnError() {
        val events = Events()
        val renderer = load(Kind.HTML, events)

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { renderer.showFullscreen(it) }
                assertTrue("not shown", events.shown.await(15, TimeUnit.SECONDS))
            } finally {
                val screens = adScreens()
                onMain { screens.forEach(Activity::finish) }
                waitFor("the ad screen to go") { Unit.takeIf { adScreens().isEmpty() } }
                onMain { renderer.destroy() }
            }
        }

        queued.assertShowRequested(queued.next())
        queued.assertImpression(queued.next())
        queued.assertNoMore()
        assertOneErrorPerShowFailure(events, showFailures = 0)
    }

    /** Every `ad_show_failed` of a case is matched by one `onError`, and no `onError` goes without one. */
    private fun assertOneErrorPerShowFailure(events: Events, showFailures: Int) {
        assertEquals("onError calls against ad_show_failed events", showFailures, events.errors.get())
    }

    /** Every `ad_show_failed` and `ad_load_failed` of a case is matched by one `onError`, and no `onError` goes without. */
    private fun assertOneErrorPerShowOrLoadFailure(events: Events, showFailures: Int, loadFailures: Int) {
        assertEquals("onError calls against failure events", showFailures + loadFailures, events.errors.get())
    }

    private fun newRenderer(kind: Kind): AdRenderer = when (kind) {
        Kind.HTML -> HtmlRenderer(context, AdRenderingConfig(), queued.adEvents(kind.renderer))
        Kind.MRAID -> MraidRenderer(context, AdRenderingConfig(), queued.adEvents(kind.renderer))
        Kind.VAST -> VastRenderer(context, AdRenderingConfig(), queued.adEvents(kind.renderer))
    }

    /** Loads a playable ad of [kind] into a new renderer and waits for it. */
    private fun load(kind: Kind, events: Events): AdRenderer {
        val renderer = newRenderer(kind)
        val markup = when (kind) {
            Kind.HTML -> "<html><body><p>ad</p></body></html>"
            Kind.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
            Kind.VAST -> {
                server.serve("/video.mp4", "video/mp4", instrumentation.context.assets.open("video.mp4").use { it.readBytes() })
                vast("video/mp4", "/video.mp4")
            }
        }
        instrumentation.runOnMainSync { renderer.load(markup, events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))
        return renderer
    }

    private fun vast(mediaType: String, mediaPath: String) = """
        <VAST version="4.2"><Ad id="1"><InLine>
        <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
        <Impression><![CDATA[${server.url("/impression")}]]></Impression>
        <Creatives><Creative><Linear skipoffset="00:00:01">
        <Duration>00:00:10</Duration>
        <MediaFiles><MediaFile delivery="progressive" type="$mediaType" width="64" height="64"><![CDATA[${server.url(mediaPath)}]]></MediaFile></MediaFiles>
        </Linear></Creative></Creatives>
        </InLine></Ad></VAST>
    """.trimIndent()

    /** The ad screen in front, if one is. */
    private fun resumedAdScreen(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is FullscreenAdActivity || it is MraidAdActivity || it is VideoAdActivity }
    }

    /** Every ad screen that has not been destroyed yet. */
    private fun adScreens(): List<Activity> = onMain {
        Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filter { it is FullscreenAdActivity || it is MraidAdActivity || it is VideoAdActivity }
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
        const val WAIT_BEFORE_SHOW_MS = 300L
        const val MAX_SINCE_LOADED_MS = 30_000L
    }
}
