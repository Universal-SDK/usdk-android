package org.bidon.rendering.html

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.LocalHttpServer
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.ui.BlankActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A creative whose page finished loading with nothing on it that can be seen is a failed load, not a loaded ad. The
 * WebView is never attached to a window here, as in production while the ad loads.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class BlankCreativeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(context.cacheDir, "blank-creative-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }
    private val server = LocalHttpServer()
    private val renderers = mutableListOf<AdRenderer>()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { renderers.forEach { it.destroy() } }
        server.close()
        directory.deleteRecursively()
    }

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val failed = CountDownLatch(1)

        val loadedCalls = AtomicInteger()
        val errorCalls = AtomicInteger()

        override fun onLoaded() {
            loadedCalls.incrementAndGet()
            loaded.countDown()
        }
        override fun onImpression() = Unit
        override fun onClick() = Unit
        override fun onClose() = Unit
        override fun onReward() = Unit
        override fun onError(error: Throwable) {
            errorCalls.incrementAndGet()
            failed.countDown()
        }
    }

    private enum class Kind(val renderer: Renderer) { HTML(Renderer.HTML), MRAID(Renderer.MRAID) }

    // A page with nothing on it

    @Test
    fun anEmptyHtmlBodyIsBlank() = isBlank(Kind.HTML, page(""))

    @Test
    fun anEmptyMraidBodyIsBlank() = isBlank(Kind.MRAID, page(""))

    /** The script's own text and the title it sets are in elements that are never drawn. */
    @Test
    fun anHtmlPageWithOnlyAScriptIsBlank() = isBlank(Kind.HTML, SCRIPT_ONLY)

    @Test
    fun anMraidPageWithOnlyAScriptIsBlank() = isBlank(Kind.MRAID, SCRIPT_ONLY)

    @Test
    fun anHtmlPageWithOnlyATrackingPixelIsBlank() = isBlank(Kind.HTML, trackingPixelPage())

    @Test
    fun anMraidPageWithOnlyATrackingPixelIsBlank() = isBlank(Kind.MRAID, trackingPixelPage())

    @Test
    fun anHtmlPageWhoseOnlyImageIsNotFoundIsBlank() = isBlank(Kind.HTML, missingImagePage())

    @Test
    fun anMraidPageWhoseOnlyImageIsNotFoundIsBlank() = isBlank(Kind.MRAID, missingImagePage())

    @Test
    fun anEmptyHtmlPageWithATransparentBodyIsBlank() = isBlank(Kind.HTML, TRANSPARENT_EMPTY)

    @Test
    fun anEmptyMraidPageWithATransparentBodyIsBlank() = isBlank(Kind.MRAID, TRANSPARENT_EMPTY)

    /** Documents the bound: a creative that fills in later than the settle window is already a failed load. */
    @Test
    fun anHtmlPageThatFillsInAfterTheWindowIsBlank() = isBlank(Kind.HTML, textAfter(AFTER_WINDOW_MILLIS))

    @Test
    fun anMraidPageThatFillsInAfterTheWindowIsBlank() = isBlank(Kind.MRAID, textAfter(AFTER_WINDOW_MILLIS))

    // A page whose content is all hidden

    @Test
    fun anHtmlPageWhoseBodyIsDisplayNoneIsBlank() = isBlank(Kind.HTML, BODY_DISPLAY_NONE)

    @Test
    fun anMraidPageWhoseBodyIsDisplayNoneIsBlank() = isBlank(Kind.MRAID, BODY_DISPLAY_NONE)

    @Test
    fun anHtmlPageWithTextOnAHiddenBodyIsBlank() = isBlank(Kind.HTML, BODY_VISIBILITY_HIDDEN)

    @Test
    fun anMraidPageWithTextOnAHiddenBodyIsBlank() = isBlank(Kind.MRAID, BODY_VISIBILITY_HIDDEN)

    @Test
    fun anHtmlPageWithTextInsideAnInvisibleElementIsBlank() = isBlank(Kind.HTML, TEXT_IN_OPACITY_ZERO)

    @Test
    fun anMraidPageWithTextInsideAnInvisibleElementIsBlank() = isBlank(Kind.MRAID, TEXT_IN_OPACITY_ZERO)

    @Test
    fun anHtmlPageWithTextInsideAnElementThatIsNotDisplayedIsBlank() = isBlank(Kind.HTML, TEXT_IN_DISPLAY_NONE)

    @Test
    fun anMraidPageWithTextInsideAnElementThatIsNotDisplayedIsBlank() = isBlank(Kind.MRAID, TEXT_IN_DISPLAY_NONE)

    // A page with something on it

    @Test
    fun anHtmlPageWithAMissingImageAndTextLoads() = hasContent(Kind.HTML, missingImageAndTextPage())

    @Test
    fun anMraidPageWithAMissingImageAndTextLoads() = hasContent(Kind.MRAID, missingImageAndTextPage())

    @Test
    fun anHtmlPageThatFillsInAfterAFewHundredMillisecondsLoads() = hasContent(Kind.HTML, textAfter(800))

    @Test
    fun anMraidPageThatFillsInAfterAFewHundredMillisecondsLoads() = hasContent(Kind.MRAID, textAfter(800))

    @Test
    fun anHtmlPageBuiltWhenTheDocumentIsParsedLoads() = hasContent(Kind.HTML, BUILT_ON_PARSED)

    @Test
    fun anMraidPageBuiltWhenTheDocumentIsParsedLoads() = hasContent(Kind.MRAID, BUILT_ON_PARSED)

    @Test
    fun anHtmlPageWrittenWithDocumentWriteLoads() = hasContent(Kind.HTML, WRITTEN)

    @Test
    fun anMraidPageWrittenWithDocumentWriteLoads() = hasContent(Kind.MRAID, WRITTEN)

    @Test
    fun anHtmlPageWithTextOnATransparentBodyLoads() = hasContent(Kind.HTML, TRANSPARENT_WITH_TEXT)

    @Test
    fun anMraidPageWithTextOnATransparentBodyLoads() = hasContent(Kind.MRAID, TRANSPARENT_WITH_TEXT)

    @Test
    fun anHtmlPageWithOnlyASolidColorLoads() = hasContent(Kind.HTML, SOLID_COLOR)

    @Test
    fun anMraidPageWithOnlyASolidColorLoads() = hasContent(Kind.MRAID, SOLID_COLOR)

    /** The WebView is 0 by 0 while the ad loads, so a height in viewport units has nothing to resolve against. */
    @Test
    fun anHtmlImageCreativeStretchedToTheViewportLoads() = hasContent(Kind.HTML, stretchedImagePage())

    @Test
    fun anMraidImageCreativeStretchedToTheViewportLoads() = hasContent(Kind.MRAID, stretchedImagePage())

    @Test
    fun anHtmlPageWithOnlyACanvasLoads() = hasContent(Kind.HTML, CANVAS)

    @Test
    fun anMraidPageWithOnlyACanvasLoads() = hasContent(Kind.MRAID, CANVAS)

    @Test
    fun anHtmlPageWithOnlyAnIframeFromAnotherOriginLoads() = hasContent(Kind.HTML, iframePage())

    @Test
    fun anMraidPageWithOnlyAnIframeFromAnotherOriginLoads() = hasContent(Kind.MRAID, iframePage())

    @Test
    fun anMraidPageThatDrawsOnlyWhenTheReadyEventFiresLoads() = hasContent(Kind.MRAID, DRAWN_ON_READY)

    @Test
    fun anMraidPageHiddenUntilTheReadyEventFiresLoads() = hasContent(Kind.MRAID, HIDDEN_UNTIL_READY)

    /** Visibility is inherited, and a child can turn it back on. */
    @Test
    fun anHtmlPageWithAVisibleChildOfAHiddenParentLoads() = hasContent(Kind.HTML, VISIBLE_CHILD_OF_HIDDEN)

    @Test
    fun anMraidPageWithAVisibleChildOfAHiddenParentLoads() = hasContent(Kind.MRAID, VISIBLE_CHILD_OF_HIDDEN)

    @Test
    fun anHtmlPageWithOnlyABackgroundImageLoads() = hasContent(Kind.HTML, backgroundImagePage())

    @Test
    fun anMraidPageWithOnlyABackgroundImageLoads() = hasContent(Kind.MRAID, backgroundImagePage())

    @Test
    fun anHtmlPageWithOnlyAnInlineSvgLoads() = hasContent(Kind.HTML, INLINE_SVG)

    @Test
    fun anMraidPageWithOnlyAnInlineSvgLoads() = hasContent(Kind.MRAID, INLINE_SVG)

    @Test
    fun anHtmlPageWithOnlyAVideoLoads() = hasContent(Kind.HTML, VIDEO)

    @Test
    fun anMraidPageWithOnlyAVideoLoads() = hasContent(Kind.MRAID, VIDEO)

    @Test
    fun anHtmlPageWithOnlyAnEmbedLoads() = hasContent(Kind.HTML, EMBED)

    @Test
    fun anMraidPageWithOnlyAnEmbedLoads() = hasContent(Kind.MRAID, EMBED)

    @Test
    fun anHtmlPageWithOnlyAnObjectLoads() = hasContent(Kind.HTML, OBJECT)

    @Test
    fun anMraidPageWithOnlyAnObjectLoads() = hasContent(Kind.MRAID, OBJECT)

    @Test
    fun anHtmlPageWhoseOnlyTextIsInAShadowRootLoads() = hasContent(Kind.HTML, SHADOW_ROOT)

    @Test
    fun anMraidPageWhoseOnlyTextIsInAShadowRootLoads() = hasContent(Kind.MRAID, SHADOW_ROOT)

    @Test
    fun anHtmlPageWhoseOnlyContentIsGeneratedTextLoads() = hasContent(Kind.HTML, GENERATED_TEXT)

    @Test
    fun anMraidPageWhoseOnlyContentIsGeneratedTextLoads() = hasContent(Kind.MRAID, GENERATED_TEXT)

    /** The creative is invisible when the page finishes and fades in on its own. */
    @Test
    fun anHtmlPageThatFadesInWithAnAnimationLoads() = hasContent(Kind.HTML, FADING_IN)

    @Test
    fun anMraidPageThatFadesInWithAnAnimationLoads() = hasContent(Kind.MRAID, FADING_IN)

    /** A page that cannot be read is given the benefit of the doubt. */
    @Test
    fun anHtmlPageWhoseStylesCannotBeReadLoads() = hasContent(Kind.HTML, UNREADABLE_STYLES)

    @Test
    fun anMraidPageWhoseStylesCannotBeReadLoads() = hasContent(Kind.MRAID, UNREADABLE_STYLES)

    @Test
    fun anMraidPageThatDrawsOnlyOnceViewableLoads() = hasContent(Kind.MRAID, DRAWN_WHEN_VIEWABLE)

    /** The creative asks once whether it is on screen and draws nothing yet; it is waiting to be shown. */
    @Test
    fun anMraidPageThatOnlyAsksWhetherItIsViewableLoads() = hasContent(Kind.MRAID, ASKS_IF_VIEWABLE)

    /**
     * A creative that only listens for `viewableChange` is waiting to be shown, as one that asks is. Known limit: an
     * MRAID creative that asks about viewability before it is on screen is not judged, so an empty one with a
     * viewability tracker passes.
     */
    @Test
    fun anMraidPageThatOnlyListensForViewabilityLoads() = hasContent(Kind.MRAID, LISTENS_FOR_VIEWABLE)

    // The events the creative hears while it is read

    @Test
    fun anMraidCreativeHearsItsStateAndReadyOnceInOrder() {
        val events = Events()
        val renderer = renderer(Kind.MRAID)
        load(renderer, RECORDS_EVENTS, events)
        assertTrue("the ad did not load", events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))

        assertEquals("stateChange:default,ready", heard(renderer, "stateOrReady"))
    }

    @Test
    fun anMraidCreativeHearsNoStateChangeWhenTheBannerIsPlacedInAWindow() {
        val events = Events()
        val renderer = renderer(Kind.MRAID)
        load(renderer, RECORDS_EVENTS, events)
        assertTrue("the ad did not load", events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))
        val viewableBefore = heard(renderer, "viewable").split(",").count { it.isNotEmpty() }
        assertEquals("viewableChange heard before the banner was placed", 0, viewableBefore)

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            scenario.onActivity {
                val container = FrameLayout(it)
                it.setContentView(container)
                renderer.attach(container)
            }
            // The viewability the creative hears on placement proves the placement has reached the page.
            waitUntil("the placement never reached the creative") {
                heard(renderer, "viewable").split(",").count { it.isNotEmpty() } > viewableBefore
            }

            assertEquals("stateChange:default,ready", heard(renderer, "stateOrReady"))
        }
        queued.assertImpression(queued.next())
        queued.assertNoMore()
    }

    // The wait

    /** The ad is gone: it reports nothing, neither the load nor its failure, however the wait would have ended. */
    @Test
    fun destroyingTheHtmlRendererDuringTheWaitReportsNothing() = destroyingDuringTheWaitReportsNothing(Kind.HTML)

    @Test
    fun destroyingTheMraidRendererDuringTheWaitReportsNothing() = destroyingDuringTheWaitReportsNothing(Kind.MRAID)

    /** A page that fails while it is being read is never handed out, and the failure is reported once. */
    @Test
    fun anHtmlPageThatFailsWhileItIsReadIsReportedOnce() = aPageThatFailsWhileItIsReadIsReportedOnce(Kind.HTML)

    @Test
    fun anMraidPageThatFailsWhileItIsReadIsReportedOnce() = aPageThatFailsWhileItIsReadIsReportedOnce(Kind.MRAID)

    /** The render process dying is the failure, not the blank page it leaves behind. */
    @Test
    fun anHtmlRenderProcessKilledWhileThePageIsReadIsReportedAsGone() = aProcessKilledWhileThePageIsRead(Kind.HTML)

    @Test
    fun anMraidRenderProcessKilledWhileThePageIsReadIsReportedAsGone() = aProcessKilledWhileThePageIsRead(Kind.MRAID)

    /** An ad with content is shown at once; only an empty page waits out the window. */
    @Test
    fun aPageWithContentLoadsWithoutWaitingForTheWindow() {
        // The first WebView of a process is slow to start; this test times a warm one.
        val warmUp = Events()
        load(renderer(Kind.HTML), page("<p>warm</p>"), warmUp)
        assertTrue("the warm-up did not load", warmUp.loaded.await(10, TimeUnit.SECONDS))

        val events = Events()
        val renderer = renderer(Kind.HTML)
        val start = SystemClock.elapsedRealtime()
        load(renderer, page("<p>ad</p>"), events)

        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))
        val took = SystemClock.elapsedRealtime() - start
        assertTrue("took $took ms, the empty-page wait is ${ContentProbe.EMPTY_PAGE_WAIT_MILLIS} ms", took < FAST_LOAD_MILLIS)
    }

    /** Another page in the same view is not the creative loading again: it is read once and reported once. */
    @Test
    fun aSecondPageFinishingInTheViewIsNotReadAgain() {
        val events = Events()
        val renderer = renderer(Kind.HTML)
        load(renderer, page("<p>ad</p>"), events)
        assertTrue("the ad did not load", events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))

        // A page with content: a second read would report a second load.
        onMain { (renderer.getView() as WebView).loadUrl("data:text/html,<p>second</p>") }
        waitUntil("the second page never finished") {
            onMain { (renderer.getView() as WebView).let { it.url.orEmpty().startsWith("data:") && it.progress == 100 } }
        }
        SystemClock.sleep(SECOND_PAGE_SETTLE_MILLIS)

        assertEquals("onLoaded count", 1, events.loadedCalls.get())
        assertEquals("onError called", 1L, events.failed.count)
        assertEquals("onError count", 0, events.errorCalls.get())
        queued.assertNoMore()
    }

    /** The same rule for MRAID: the page that finishes in the view after the creative is not read again. */
    @Test
    fun aSecondPageFinishingInTheMraidViewIsNotReadAgain() {
        val events = Events()
        val renderer = renderer(Kind.MRAID)
        load(renderer, page("<p>ad</p>"), events)
        assertTrue("the ad did not load", events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))

        onMain { (renderer.getView() as WebView).loadUrl("data:text/html,<p>second</p>") }
        waitUntil("the second page never finished") {
            onMain { (renderer.getView() as WebView).let { it.url.orEmpty().startsWith("data:") && it.progress == 100 } }
        }
        SystemClock.sleep(SECOND_PAGE_SETTLE_MILLIS)

        assertEquals("onLoaded count", 1, events.loadedCalls.get())
        assertEquals("onError called", 1L, events.failed.count)
        assertEquals("onError count", 0, events.errorCalls.get())
        queued.assertNoMore()
    }

    // The same renderer loading again

    @Test
    fun anHtmlRendererLoadingAgainReportsTheSecondLoad() = loadingAgainReportsTheSecondLoad(Kind.HTML)

    @Test
    fun anMraidRendererLoadingAgainReportsTheSecondLoad() = loadingAgainReportsTheSecondLoad(Kind.MRAID)

    // Content the page is still fetching, or that is not drawn

    /** Content inside `noscript` is never shown when scripts run, and the WebView runs them. */
    @Test
    fun anHtmlPageWithOnlyAPixelInNoscriptIsBlank() = isBlank(Kind.HTML, noscriptPixelPage())

    @Test
    fun anMraidPageWithOnlyAPixelInNoscriptIsBlank() = isBlank(Kind.MRAID, noscriptPixelPage())

    /** An image added when the page has finished and still downloading is on its way to being shown. */
    @Test
    fun anHtmlImageCreativeStillDownloadingLoads() = hasContent(Kind.HTML, stalledImagePage())

    @Test
    fun anMraidImageCreativeStillDownloadingLoads() = hasContent(Kind.MRAID, stalledImagePage())

    @Test
    fun anHtmlPageWithOnlyALazyImageLoads() = hasContent(Kind.HTML, lazyImagePage())

    @Test
    fun anMraidPageWithOnlyALazyImageLoads() = hasContent(Kind.MRAID, lazyImagePage())

    @Test
    fun anHtmlPageWhoseOnlyTextIsInAClosedShadowRootLoads() = hasContent(Kind.HTML, CLOSED_SHADOW_ROOT)

    @Test
    fun anMraidPageWhoseOnlyTextIsInAClosedShadowRootLoads() = hasContent(Kind.MRAID, CLOSED_SHADOW_ROOT)

    // A page that stops answering

    /** A page the probe cannot read at all is given the benefit of the doubt once the wait is over. */
    @Test
    fun anHtmlPageThatBusyLoopsAfterLoadingStillGetsAnAnswer() = aBusyPageStillGetsAnAnswer(Kind.HTML)

    @Test
    fun anMraidPageThatBusyLoopsAfterLoadingStillGetsAnAnswer() = aBusyPageStillGetsAnAnswer(Kind.MRAID)

    // A second load while the first is still in flight, and a stale view's failure

    /** The page of the first load cannot finish; the ad that was asked for last is the one that is reported. */
    @Test
    fun anHtmlRendererLoadingAgainWhileTheFirstLoadIsInFlightReportsOnlyTheSecond() = loadingAgainDuringTheFirstLoad(Kind.HTML)

    @Test
    fun anMraidRendererLoadingAgainWhileTheFirstLoadIsInFlightReportsOnlyTheSecond() = loadingAgainDuringTheFirstLoad(Kind.MRAID)

    /** The first view is not the ad any more: what happens to it says nothing about the second load. */
    @Test
    fun anHtmlViewReplacedByASecondLoadFailingDoesNotFailTheSecondLoad() = aReplacedViewFailing(Kind.HTML)

    @Test
    fun anMraidViewReplacedByASecondLoadFailingDoesNotFailTheSecondLoad() = aReplacedViewFailing(Kind.MRAID)

    // What size an image says it is

    /** The stylesheet sizes the picture; the attributes are only a hint. */
    @Test
    fun anHtmlImageWithPixelAttributesSizedByItsStylesheetLoads() = hasContent(Kind.HTML, imageSizedByStyleOverPixelAttributes())

    @Test
    fun anMraidImageWithPixelAttributesSizedByItsStylesheetLoads() = hasContent(Kind.MRAID, imageSizedByStyleOverPixelAttributes())

    /** An empty attribute is not a pixel size. */
    @Test
    fun anHtmlImageWithEmptySizeAttributesStillDownloadingLoads() = hasContent(Kind.HTML, stalledImageWithEmptySizePage())

    @Test
    fun anMraidImageWithEmptySizeAttributesStillDownloadingLoads() = hasContent(Kind.MRAID, stalledImageWithEmptySizePage())

    @Test
    fun anHtmlPageWithOnlyAStalledTrackingPixelAddedAfterLoadIsBlank() = isBlank(Kind.HTML, stalledTrackingPixelPage())

    @Test
    fun anMraidPageWithOnlyAStalledTrackingPixelAddedAfterLoadIsBlank() = isBlank(Kind.MRAID, stalledTrackingPixelPage())

    // A failure before the page has finished

    @Test
    fun anHtmlMainFrameErrorBeforeThePageFinishesIsReportedOnceAndNeverLoads() = anErrorBeforeThePageFinishes(Kind.HTML)

    @Test
    fun anMraidMainFrameErrorBeforeThePageFinishesIsReportedOnceAndNeverLoads() = anErrorBeforeThePageFinishes(Kind.MRAID)

    /** Nothing on the page and the script holds the thread, so a page that was read would be blank: only the watchdog can answer. */
    @Test
    fun anHtmlEmptyPageThatBlocksItsThreadInTheLoadEventLoadsOnTheWatchdog() = aBlockedEmptyPageLoadsOnTheWatchdog(Kind.HTML)

    @Test
    fun anMraidEmptyPageThatBlocksItsThreadInTheLoadEventLoadsOnTheWatchdog() = aBlockedEmptyPageLoadsOnTheWatchdog(Kind.MRAID)

    private fun isBlank(kind: Kind, document: String) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, document, events)

        val failed = events.failed.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + FAILURE_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
        val loadedMessage = "onLoaded called: ${events.loaded.count == 0L}"
        assertTrue("a blank creative was not reported as failed ($loadedMessage)", failed)
        assertEquals("onLoaded called for a blank creative", 1L, events.loaded.count)
        assertEquals("onError count", 1, events.errorCalls.get())

        val event = queued.next()
        queued.assertAdEvent(event, "ad_load_failed", EventClass.ERROR, null)
        assertEquals("6003", event.json.getString("error_code"))
        assertEquals("creative", event.json.getString("error_domain"))
        assertEquals("webview_load", event.json.getString("failed_stage"))
        assertFalse(event.json.has("external_error_code"))
        assertFalse(event.json.has("external_error_source"))
        assertNull("the blank view was handed out", onMain { renderer.getView() })

        // A creative that fills in late is not loaded after it was reported failed.
        assertFalse("onLoaded after the failure", events.loaded.await(AFTER_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
        queued.assertNoMore()
    }

    private fun hasContent(kind: Kind, document: String) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, document, events)

        val loaded = events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
        assertTrue("the ad did not load", loaded)
        assertEquals("onError called for a creative with content", 1L, events.failed.count)
        assertEquals("onError count", 0, events.errorCalls.get())
        assertNotNull("the loaded view was not handed out", onMain { renderer.getView() })
        queued.assertNoMore()
    }

    private fun loadingAgainReportsTheSecondLoad(kind: Kind) {
        val first = Events()
        val renderer = renderer(kind)
        load(renderer, page("<p>ad</p>"), first)
        assertTrue("the first ad did not load", first.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))

        val second = Events()
        load(renderer, page("<p>another ad</p>"), second)

        val loaded = second.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
        assertTrue("the second load was not reported", loaded)
        assertEquals("onError called on the second load", 1L, second.failed.count)
        assertEquals("onLoaded count on the first listener", 1, first.loadedCalls.get())
    }

    private fun aBlockedEmptyPageLoadsOnTheWatchdog(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, BLOCKS_IN_LOAD_EVENT, events)
        try {
            val loaded = events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + BUSY_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
            assertTrue("a page that does not answer was never given an answer", loaded)
            assertEquals("onError count", 0, events.errorCalls.get())
            assertEquals("onLoaded count", 1, events.loadedCalls.get())
        } finally {
            // The render process is shared, and a blocked page would hold up the tests after this one until it lets go.
            SystemClock.sleep(BUSY_LOOP_MILLIS)
        }
    }

    private fun loadingAgainDuringTheFirstLoad(kind: Kind) {
        server.stall("/first.png")
        val first = Events()
        val renderer = renderer(kind)
        // The image is given up on after a second, so the first page does finish, late, while the second is the ad.
        load(
            renderer,
            page(
                "<img id='held' src='${server.url("/first.png")}'>" +
                    "<script>setTimeout(function () { document.getElementById('held').src = '${server.url("/gone.png")}' }, 1000)</script>",
            ),
            first,
        )
        waitUntil("the first page never asked for its image") { server.requestCount("/first.png") > 0 }
        val firstView = onMain { renderer.getView() }

        // The second page finishes after the first, so the first view's finish arrives while the second is the ad.
        server.stall("/second.png")
        val second = Events()
        load(
            renderer,
            page(
                "<p>b</p><img id='held' src='${server.url("/second.png")}'>" +
                    "<script>setTimeout(function () { document.getElementById('held').src = '${server.url("/gone.png")}' }, " +
                    "$SECOND_PAGE_FINISH_MILLIS)</script>",
            ),
            second,
        )

        assertTrue(
            "the second load was not reported",
            second.loaded.await(SECOND_PAGE_FINISH_MILLIS + ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
        )
        SystemClock.sleep(AFTER_WINDOW_MILLIS)
        assertEquals("onLoaded count on the second listener", 1, second.loadedCalls.get())
        assertEquals("onError count on the second listener", 0, second.errorCalls.get())
        val view = onMain { renderer.getView() }
        assertNotNull("the second view was not handed out", view)
        assertTrue("the view is the first load's", view !== firstView)
        assertEquals("onLoaded count on the first listener", 0, first.loadedCalls.get())
        assertEquals("onError count on the first listener", 0, first.errorCalls.get())
    }

    private fun aReplacedViewFailing(kind: Kind) {
        val first = Events()
        val renderer = renderer(kind)
        load(renderer, page("<p>a</p>"), first)
        assertTrue("the first ad did not load", first.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))
        val firstView = onMain { renderer.getView() as WebView }

        val second = Events()
        load(renderer, textAfter(800), second)
        waitUntil("the second page never finished") { onMain { (renderer.getView() as? WebView)?.progress == 100 } }
        onMain { firstView.loadUrl(UNREACHABLE) }

        assertTrue("the second load was not reported", second.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + LOAD_MARGIN_MILLIS, TimeUnit.MILLISECONDS))
        SystemClock.sleep(AFTER_WINDOW_MILLIS)
        assertEquals("onLoaded count on the second listener", 1, second.loadedCalls.get())
        assertEquals("onError count on the second listener", 0, second.errorCalls.get())
        assertEquals("onError count on the first listener", 0, first.errorCalls.get())
        assertEquals("onLoaded count on the first listener", 1, first.loadedCalls.get())
        assertNotNull("the second view was not handed out", onMain { renderer.getView() })
    }

    private fun anErrorBeforeThePageFinishes(kind: Kind) {
        server.stall("/hold.png")
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, page("<img src='${server.url("/hold.png")}'>"), events)
        waitUntil("the page never asked for its image") { server.requestCount("/hold.png") > 0 }

        onMain { (renderer.getView() as WebView).loadUrl(UNREACHABLE) }

        assertTrue("the failure was not reported", events.failed.await(10, TimeUnit.SECONDS))
        assertFalse("onLoaded after the failure", events.loaded.await(AFTER_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals("onError count", 1, events.errorCalls.get())
        val event = queued.next()
        queued.assertAdEvent(event, "ad_load_failed", EventClass.ERROR, null)
        assertDeadAddressFailure(event)
        queued.assertNoMore()
    }

    private fun imageSizedByStyleOverPixelAttributes(): String {
        server.serve("/sized.png", "image/png", png(40))
        return page("<img src='${server.url("/sized.png")}' width='1' height='1' style='width: 300px; height: 250px'>")
    }

    private fun stalledImageWithEmptySizePage(): String {
        server.stall("/empty-size.png")
        return page(
            "<script>window.onload = function () {" +
                "var image = new Image(); image.setAttribute('width', ''); image.setAttribute('height', '');" +
                "image.src = '${server.url("/empty-size.png")}'; document.body.appendChild(image) }</script>",
        )
    }

    private fun stalledTrackingPixelPage(): String {
        server.stall("/pixel-slow.png")
        return page(
            "<script>window.onload = function () {" +
                "var i = new Image(); i.width = 1; i.height = 1; i.src = '${server.url("/pixel-slow.png")}';" +
                "document.body.appendChild(i) }</script>",
        )
    }

    private fun aBusyPageStillGetsAnAnswer(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, BUSY_AFTER_LOAD, events)
        try {
            val loaded = events.loaded.await(ContentProbe.EMPTY_PAGE_WAIT_MILLIS + BUSY_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
            assertTrue("a page that does not answer was never given an answer", loaded)
            assertEquals("onError count", 0, events.errorCalls.get())
        } finally {
            // The render process is shared, and a blocked page would hold up the tests after this one until it lets go.
            SystemClock.sleep(BUSY_LOOP_MILLIS)
        }
    }

    private fun destroyingDuringTheWaitReportsNothing(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, blankPageSignallingTheRead(), events)
        waitUntilTheBlankPageIsRead()

        renderers.remove(renderer)
        instrumentation.runOnMainSync { renderer.destroy() }

        assertFalse("onLoaded after destroy", events.loaded.await(AFTER_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals("onError after destroy", 1L, events.failed.count)
        assertEquals("onError count", 0, events.errorCalls.get())
        queued.assertNoMore()
    }

    private fun aPageThatFailsWhileItIsReadIsReportedOnce(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, blankPageSignallingTheRead(), events)
        waitUntilTheBlankPageIsRead()

        // A navigation the page starts without a tap is refused, so the main frame is sent to the dead address from outside.
        onMain { (renderer.getView() as WebView).loadUrl(UNREACHABLE) }

        assertTrue("the failure was not reported", events.failed.await(10, TimeUnit.SECONDS))
        SystemClock.sleep(SECOND_PAGE_SETTLE_MILLIS)
        assertEquals("onError count", 1, events.errorCalls.get())
        val event = queued.next()
        queued.assertAdEvent(event, "ad_load_failed", EventClass.ERROR, null)
        assertDeadAddressFailure(event)
        assertNull("the failed view was handed out", onMain { renderer.getView() })

        assertFalse("onLoaded after the failure", events.loaded.await(AFTER_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
        queued.assertNoMore()
    }

    /** The main frame sent to the dead address fails on the host lookup: the network's failure, with the WebView's code. */
    private fun assertDeadAddressFailure(event: QueuedEvents.Stored) {
        assertEquals("8003", event.json.getString("error_code"))
        assertEquals("network", event.json.getString("error_domain"))
        assertEquals("webview_load", event.json.getString("failed_stage"))
        assertEquals("webview", event.json.getString("external_error_source"))
        assertTrue("the WebView's code is not a number", event.json.getString("external_error_code").toIntOrNull() != null)
    }

    private fun aProcessKilledWhileThePageIsRead(kind: Kind) {
        val events = Events()
        val renderer = renderer(kind)
        load(renderer, blankPageSignallingTheRead(), events)
        waitUntilTheBlankPageIsRead()

        onMain { (renderer.getView() as WebView).loadUrl("chrome://crash") }

        assertTrue("the death was not handled", events.failed.await(30, TimeUnit.SECONDS))
        SystemClock.sleep(SECOND_PAGE_SETTLE_MILLIS)
        assertEquals("onError count", 1, events.errorCalls.get())
        val gone = queued.next()
        queued.assertAdEvent(gone, "render_crashed", EventClass.ERROR, kind.renderer)
        assertEquals(true, gone.json.getBoolean("did_crash"))
        assertEquals(false, gone.json.getBoolean("was_shown"))
        assertTrue(gone.json.toString(), gone.json.getString("renderer_priority_at_exit") in setOf("waived", "bound", "important"))
        assertFalse("onLoaded after the death", events.loaded.await(AFTER_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
        queued.assertNoMore()
    }

    /** What the creative recorded for [what], comma separated; the page keeps its own log in `window.heard`. */
    private fun heard(renderer: AdRenderer, what: String): String {
        val answer = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            (renderer.getView() as WebView).evaluateJavascript("window.heard.$what.join(',')") {
                result = it.trim('"')
                answer.countDown()
            }
        }
        assertTrue("the page did not answer", answer.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(20)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun renderer(kind: Kind): AdRenderer = when (kind) {
        Kind.HTML -> HtmlRenderer(context, AdRenderingConfig(), queued.adEvents(kind.renderer))
        Kind.MRAID -> MraidRenderer(context, AdRenderingConfig(), queued.adEvents(kind.renderer))
    }.also { renderers += it }

    private fun load(renderer: AdRenderer, document: String, events: Events) =
        instrumentation.runOnMainSync { renderer.load(document, events) }

    private fun page(body: String, head: String = "") = "<html><head>$head</head><body>$body</body></html>"

    private fun png(size: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.RED) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun trackingPixelPage(): String {
        server.serve("/pixel.png", "image/png", png(1))
        return page("<img src='${server.url("/pixel.png")}' width='1' height='1'>")
    }

    /**
     * A page with nothing on it that asks for [READ_SIGNAL] the first time its styles are read. Nothing but the content
     * probe reads them, so the request shows the probe is polling.
     */
    private fun blankPageSignallingTheRead() = page(
        "<script>(function () {" +
            "var read = window.getComputedStyle, sent = false;" +
            "window.getComputedStyle = function () {" +
            "if (!sent) { sent = true; new Image().src = '${server.url(READ_SIGNAL)}' }" +
            "return read.apply(window, arguments) } })()</script>",
    )

    private fun waitUntilTheBlankPageIsRead() =
        waitUntil("the page was never read") { server.requestCount(READ_SIGNAL) > 0 }

    private fun backgroundImagePage(): String {
        val image = Base64.encodeToString(png(40), Base64.NO_WRAP)
        return page("", head = "<style>body { background-image: url(data:image/png;base64,$image) }</style>")
    }

    private fun missingImagePage() = page("<img src='${server.url("/missing.png")}'>")

    private fun missingImageAndTextPage() = page("<img src='${server.url("/missing.png")}'><p>ad</p>")

    private fun stretchedImagePage(): String {
        server.serve("/ad.png", "image/png", png(40))
        return page(
            "<img src='${server.url("/ad.png")}' style='width: 100%; height: 100vh'>",
            head = "<style>html, body { margin: 0 }</style>",
        )
    }

    private fun iframePage(): String {
        server.serve("/frame", "text/html", "<html><body><p>frame</p></body></html>".toByteArray())
        return page("<iframe src='${server.url("/frame")}'></iframe>")
    }

    private fun noscriptPixelPage(): String {
        server.serve("/p.gif", "image/gif", png(1))
        return page("<noscript><img src='${server.url("/p.gif")}' width='1' height='1'></noscript>")
    }

    /** The image is added once the page has loaded and its server never answers, so it is still downloading. */
    private fun stalledImagePage(): String {
        server.stall("/slow.png")
        return page(
            "<script>window.onload = function () {" +
                "var image = new Image(); image.src = '${server.url("/slow.png")}'; document.body.appendChild(image) }</script>",
        )
    }

    private fun lazyImagePage(): String {
        server.serve("/lazy.png", "image/png", png(40))
        return page("<img loading='lazy' src='${server.url("/lazy.png")}'>")
    }

    private fun textAfter(millis: Long) = page(
        "<script>setTimeout(function () { document.body.append('ad') }, $millis)</script>",
    )

    private companion object {
        /** How long a busy page holds its thread: well past the wait, and short enough to wait out after the test. */
        const val BUSY_LOOP_MILLIS = 5_000L
        const val BUSY_AFTER_LOAD = "<html><body><p>ad</p><script>window.onload = function () { setTimeout(function () {" +
            "var end = Date.now() + $BUSY_LOOP_MILLIS; while (Date.now() < end) {} }, 0) }</script></body></html>"
        /**
         * Empty, and holding its thread from 300 ms after the load event: the page has finished by then and the probe has
         * read it, but it can never read the page again before the wait is over, so only the watchdog can answer. A block
         * inside the load event itself would keep the page from ever finishing.
         */
        const val BLOCKS_IN_LOAD_EVENT = "<html><body><script>window.addEventListener('load', function () {" +
            "setTimeout(function () { var end = Date.now() + $BUSY_LOOP_MILLIS; while (Date.now() < end) {} }, 300) })" +
            "</script></body></html>"
        const val CLOSED_SHADOW_ROOT = "<html><body><script>" +
            "customElements.define('x-ad', class extends HTMLElement {" +
            "constructor() { super(); this.attachShadow({mode: 'closed'}).innerHTML = '<p>ad</p>' } })" +
            "</script><x-ad></x-ad></body></html>"
        /** A host that never resolves, so a main frame sent there fails on the host lookup, a network error. */
        const val UNREACHABLE = "http://creative.invalid/"
        const val READ_SIGNAL = "/page-read"
        const val BODY_DISPLAY_NONE = "<html><body style='display: none'><p>ad</p></body></html>"
        const val BODY_VISIBILITY_HIDDEN = "<html><body style='visibility: hidden'><p>ad</p></body></html>"
        const val TEXT_IN_OPACITY_ZERO = "<html><body><div style='opacity: 0'><p>ad</p></div></body></html>"
        const val TEXT_IN_DISPLAY_NONE = "<html><body><div style='display: none'><p>ad</p></div></body></html>"
        const val VISIBLE_CHILD_OF_HIDDEN = "<html><body><div style='visibility: hidden'>" +
            "<p style='visibility: visible'>ad</p></div></body></html>"
        const val INLINE_SVG = "<html><body><svg width='100' height='100'>" +
            "<rect width='100' height='100' fill='red'/></svg></body></html>"
        const val VIDEO = "<html><body><video></video></body></html>"
        const val EMBED = "<html><body><embed></body></html>"
        const val OBJECT = "<html><body><object></object></body></html>"
        const val SHADOW_ROOT = "<html><body><div id='host'></div><script>" +
            "document.getElementById('host').attachShadow({mode: 'open'}).innerHTML = '<p>ad</p>'" +
            "</script></body></html>"
        const val GENERATED_TEXT = "<html><head><style>#ad::before { content: 'ad' }</style></head>" +
            "<body><div id='ad'></div></body></html>"
        const val FADING_IN = "<html><head><style>" +
            "@keyframes fade-in { from { opacity: 0 } to { opacity: 1 } }" +
            "p { animation: fade-in 300ms 300ms both }" +
            "</style></head><body><p>ad</p></body></html>"
        const val UNREADABLE_STYLES = "<html><head><script>" +
            "window.getComputedStyle = function () { throw new Error('unreadable') }" +
            "</script></head><body></body></html>"
        const val DRAWN_WHEN_VIEWABLE = "<html><body><script>" +
            "function draw() { document.body.appendChild(document.createTextNode('ad')) }" +
            "mraid.addEventListener('ready', function () {" +
            "if (mraid.isViewable()) { draw() } else { mraid.addEventListener('viewableChange', function (v) { if (v) draw() }) }" +
            "})</script></body></html>"
        const val LISTENS_FOR_VIEWABLE = "<html><body><script>" +
            "function draw() { document.body.appendChild(document.createTextNode('ad')) }" +
            "mraid.addEventListener('viewableChange', function (v) { if (v) draw() })" +
            "</script></body></html>"
        const val ASKS_IF_VIEWABLE = "<html><body><script>" +
            "function draw() { document.body.appendChild(document.createTextNode('ad')) }" +
            "mraid.addEventListener('ready', function () { if (mraid.isViewable()) draw() })" +
            "</script></body></html>"
        const val RECORDS_EVENTS = "<html><body><p>ad</p><script>" +
            "window.heard = {stateOrReady: [], viewable: []};" +
            "mraid.addEventListener('stateChange', function (s) { window.heard.stateOrReady.push('stateChange:' + s) });" +
            "mraid.addEventListener('ready', function () { window.heard.stateOrReady.push('ready') });" +
            "mraid.addEventListener('viewableChange', function (v) { window.heard.viewable.push('viewable:' + v) })" +
            "</script></body></html>"
        const val SCRIPT_ONLY = "<html><head><script>document.title = 'ad'</script></head>" +
            "<body><script>var shown = false</script></body></html>"
        const val TRANSPARENT_EMPTY = "<html><body style='background: transparent'></body></html>"
        const val TRANSPARENT_WITH_TEXT = "<html><body style='background: transparent'><p>ad</p></body></html>"
        const val SOLID_COLOR = "<html><body style='background-color: #cc0000'></body></html>"
        const val CANVAS = "<html><body><canvas width='300' height='250'></canvas></body></html>"
        const val WRITTEN = "<html><body><script>document.write('<p>ad</p>')</script></body></html>"
        const val BUILT_ON_PARSED = "<html><head><script>document.addEventListener('DOMContentLoaded', function () {" +
            "var p = document.createElement('p'); p.textContent = 'ad'; document.body.appendChild(p) })" +
            "</script></head><body></body></html>"
        const val DRAWN_ON_READY = "<html><body><script>" +
            "function draw() { document.body.appendChild(document.createTextNode('ad')) }" +
            "if (mraid.getState() === 'loading') { mraid.addEventListener('ready', draw) } else { draw() }" +
            "</script></body></html>"
        const val HIDDEN_UNTIL_READY = "<html><body><div id='ad' style='display: none'>ad</div><script>" +
            "function show() { document.getElementById('ad').style.display = 'block' }" +
            "if (mraid.getState() === 'loading') { mraid.addEventListener('ready', show) } else { show() }" +
            "</script></body></html>"

        /** Past the settle window with room to spare, to tell a creative that is late from one that is on time. */
        val AFTER_WINDOW_MILLIS = ContentProbe.EMPTY_PAGE_WAIT_MILLIS + 1_500

        /** The window, plus the page's own load and the WebView's start. */
        const val FAILURE_MARGIN_MILLIS = 6_000L
        const val LOAD_MARGIN_MILLIS = 6_000L
        const val BUSY_MARGIN_MILLIS = 1_500L
        const val FAST_LOAD_MILLIS = 1_500L
        const val SECOND_PAGE_SETTLE_MILLIS = 500L
        /** When the second of two loads lets its page finish: well after the first page has. */
        const val SECOND_PAGE_FINISH_MILLIS = 3_000L
    }
}
