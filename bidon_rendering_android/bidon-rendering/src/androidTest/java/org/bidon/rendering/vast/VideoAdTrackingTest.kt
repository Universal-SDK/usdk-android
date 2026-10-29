package org.bidon.rendering.vast

import android.app.Activity
import android.os.Build
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.LocalHttpServer
import org.bidon.rendering.telemetry.ErrorCode
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.EventClass
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.ui.BlankActivity
import org.bidon.rendering.vast.ui.EndCardView
import org.bidon.rendering.vast.ui.VideoAdActivity
import org.bidon.rendering.vast.ui.VideoAdView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A video ad on a real screen, with its video, end card and trackers served by a local server.
 *
 * The impression is what the buyer bills on, so it has to mean that the video actually played: it
 * is counted when playback starts, not when the screen opens, and only once for the ad, even when
 * the screen is recreated and the video starts over. A click sends the trackers of what was
 * clicked: the video's for the video, the end card's for the end card. A tap that opens nothing is
 * not a click at all, and neither is a tap on a video that has not started playing.
 */
@RunWith(AndroidJUnit4::class)
class VideoAdTrackingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = LocalHttpServer()
    private val directory = File(instrumentation.targetContext.cacheDir, "video-ad-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val impressionReported = CountDownLatch(1)
        val impressions = AtomicInteger()
        val clicks = AtomicInteger()
        val failed = CountDownLatch(1)
        val closes = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() {
            impressions.incrementAndGet()
            impressionReported.countDown()
        }
        override fun onClick() {
            clicks.incrementAndGet()
        }
        override fun onClose() {
            closes.incrementAndGet()
            closed.countDown()
        }
        override fun onReward() = Unit
        override fun onError(error: Throwable) = failed.countDown()
    }

    @After
    fun stopServer() {
        server.close()
        directory.deleteRecursively()
    }

    // The click-throughs lead nowhere on purpose: nothing claims the scheme, so no browser opens
    // over the ad while the test is still clicking on it.
    private fun vast(mediaPath: String, clickThrough: String? = "bidon-test://advertiser") = """
        <VAST version="4.2"><Ad id="1"><InLine>
        <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
        <Impression><![CDATA[${server.url("/impression/dsp?cb=[CACHEBUSTING]")}]]></Impression>
        <Impression><![CDATA[${server.url("/impression/verifier")}]]></Impression>
        <Creatives>
        <Creative><Linear skipoffset="00:00:01">
        <Duration>00:00:10</Duration>
        <VideoClicks>
        ${clickThrough?.let { "<ClickThrough><![CDATA[$it]]></ClickThrough>" }.orEmpty()}
        <ClickTracking><![CDATA[${server.url("/click/video?at=[ADPLAYHEAD]")}]]></ClickTracking>
        </VideoClicks>
        <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="64" height="64"><![CDATA[${server.url(mediaPath)}]]></MediaFile></MediaFiles>
        </Linear></Creative>
        <Creative><CompanionAds><Companion width="64" height="64">
        <StaticResource creativeType="image/png"><![CDATA[${server.url("/end-card.png")}]]></StaticResource>
        ${clickThrough?.let { "<CompanionClickThrough><![CDATA[$it]]></CompanionClickThrough>" }.orEmpty()}
        <CompanionClickTracking><![CDATA[${server.url("/click/end-card?cb=[CACHEBUSTING]")}]]></CompanionClickTracking>
        </Companion></CompanionAds></Creative>
        </Creatives>
        </InLine></Ad></VAST>
    """.trimIndent()

    /** Loads [vast], shows it over a blank screen, runs [block], and tears everything down. */
    private fun showAd(vast: String, events: Events, block: () -> Unit) {
        val renderer = VastRenderer(instrumentation.targetContext, AdRenderingConfig(), queued.adEvents(Renderer.VAST))
        instrumentation.runOnMainSync { renderer.load(vast, events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))

        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { renderer.showFullscreen(it) }
                block()
            } finally {
                // Stop answering first, so a stalled video download is not left open under a closing screen.
                server.close()
                // Whatever stage a failed test left the ad screen in, it must not play on into the next
                // test. Nothing here throws, so it cannot hide the failure that led here.
                val screens = videoScreens()
                onMain { screens.forEach(Activity::finish) }
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (videoScreens().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                onMain { renderer.destroy() }
            }
        }
    }

    @Test
    fun theImpressionIsCountedOnceWhenTheVideoStarts() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            assertTrue(events.impressionReported.await(5, TimeUnit.SECONDS))
            waitFor("both impression pixels") {
                (server.requestCount("/impression/dsp") == 1 &&
                    server.requestCount("/impression/verifier") == 1).takeIf { it }
            }

            // A theme or language change recreates the screen, and the video plays from the start.
            onMain { screen.recreate() }
            waitFor("the recreated screen to play") { resumedVideoScreen()?.takeIf { it !== screen && it.isPlaying() } }
            // Pixels go out on a background thread; give a second one time to arrive.
            SystemClock.sleep(1_000)

            assertEquals(1, events.impressions.get())
            queued.assertShowRequested(queued.next())
            val impression = queued.next()
            queued.assertImpression(impression)
            queued.assertRenderDuration(impression)
            queued.assertNoMore()
            assertEquals(1, server.requestCount("/impression/dsp"))
            assertEquals(1, server.requestCount("/impression/verifier"))
            assertMacrosFilledIn("/impression/dsp")
        }
    }

    /** A response of two ads plays the pod's first one, and the buyer of the other is not told of an impression. */
    @Test
    fun onlyTheChosenAdOfAResponseCountsAnImpression() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        val events = Events()
        val inner = { xml: String -> xml.substringAfter("<VAST version=\"4.2\">").substringBeforeLast("</VAST>") }
        val unchosen = inner(vast("/other-video.mp4"))
            .replace("<Ad id=\"1\">", "<Ad id=\"2\" sequence=\"2\">")
            .replace("/impression/", "/other-impression/")
            .replace("/click/", "/other-click/")
            .replace(
                "<Duration>00:00:10</Duration>",
                "<Duration>00:00:10</Duration><TrackingEvents><Tracking event=\"start\"><![CDATA[${server.url("/other-start")}]]></Tracking></TrackingEvents>"
            )
        val chosen = inner(vast("/video.mp4")).replace("<Ad id=\"1\">", "<Ad id=\"1\" sequence=\"1\">")

        check("/other-impression/dsp" in unchosen && "/other-click/video" in unchosen && "/other-start" in unchosen) {
            "the unchosen fixture does not carry its own URLs"
        }
        check("/other-" !in chosen) { "the chosen fixture carries the unchosen ad's URLs" }

        showAd("""<VAST version="4.2">$unchosen$chosen</VAST>""", events) {
            waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            assertTrue(events.impressionReported.await(5, TimeUnit.SECONDS))
            waitFor("both impression pixels") {
                (server.requestCount("/impression/dsp") == 1 &&
                    server.requestCount("/impression/verifier") == 1).takeIf { it }
            }
            // Pixels go out on a background thread; give a stray one time to arrive.
            SystemClock.sleep(1_000)

            assertEquals(1, events.impressions.get())
            assertEquals(1, server.requestCount("/impression/dsp"))
            assertEquals(1, server.requestCount("/impression/verifier"))
            assertEquals(0, server.requestCount("/other-impression/dsp"))
            assertEquals(0, server.requestCount("/other-impression/verifier"))
            assertEquals(0, server.requestCount("/other-start"))
            assertEquals(0, server.requestCount("/other-click/video"))
            assertEquals(0, server.requestCount("/other-click/end-card"))
            assertEquals(0, server.requestCount("/other-video.mp4"))
        }
    }

    /** A wrapper with no address to fetch fails the load as the creative's, and the event carries nothing of the ad. */
    @Test
    fun aWrapperWithoutAnAddressReportsAMissingUri() {
        val failed = loadFailure("<VAST version=\"4.2\"><Ad id=\"1\"><Wrapper><AdSystem>test</AdSystem></Wrapper></Ad></VAST>")

        assertEquals("6006", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("parse", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("300", failed.json.getString("external_error_code"))
        assertEquals(VastParsingException::class.java.name, failed.json.getString("exception_class"))
        assertTrue("no load time", failed.json.getLong("duration_ms") >= 0)
    }

    @Test
    fun aDocumentThatIsNotVastReportsAnInvalidDocument() {
        val failed = loadFailure("this is not a VAST document")

        assertEquals("6004", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("parse", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("100", failed.json.getString("external_error_code"))
    }

    /** The wrapper's server answers with an error: the buyer's failure, with the status it sent. */
    @Test
    fun aWrapperTheServerRefusesReportsTheStatus() {
        listOf(404, 500).forEach { status ->
            server.respondWith("/wrapped-$status.xml", status)

            val failed = loadFailure(wrapperTo(server.url("/wrapped-$status.xml")))

            assertEquals("$status", "8002", failed.json.getString("error_code"))
            assertEquals("$status", "dsp", failed.json.getString("error_domain"))
            assertEquals("$status", "asset_fetch", failed.json.getString("failed_stage"))
            assertEquals("$status", "http", failed.json.getString("external_error_source"))
            assertEquals("$status", status.toString(), failed.json.getString("external_error_code"))
            assertEquals("$status", VastParsingException::class.java.name, failed.json.getString("exception_class"))
            assertEquals("$status", VastWrapperHttpException::class.java.name, failed.json.getString("exception_cause_class"))
            assertEquals("$status", 1, server.requestCount("/wrapped-$status.xml"))
        }
    }

    /** Nothing listens at the wrapper's address: the network's failure, with the VAST code for a fetch that got no answer. */
    @Test
    fun aWrapperNothingAnswersReportsNoAnswer() {
        val failed = loadFailure(wrapperTo("http://127.0.0.1:${closedPort()}/wrapped.xml"))

        assertEquals("8001", failed.json.getString("error_code"))
        assertEquals("network", failed.json.getString("error_domain"))
        assertEquals("asset_fetch", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("301", failed.json.getString("external_error_code"))
        assertEquals(VastParsingException::class.java.name, failed.json.getString("exception_class"))
        assertEquals("java.net.ConnectException", failed.json.getString("exception_cause_class"))
        assertTrue("no load time", failed.json.getLong("duration_ms") >= 0)
    }

    /** The test manifest refuses cleartext to `localhost`: the creative's failure, not the network's. */
    @Test
    fun aWrapperTheCleartextPolicyRefusesReports6015() {
        server.serve("/wrapped.xml", "application/xml", wrapperTo(server.url("/elsewhere.xml")).toByteArray())

        val failed = loadFailure(wrapperTo(server.url("/wrapped.xml").replace("127.0.0.1", "localhost")))

        assertEquals("6015", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("asset_fetch", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("301", failed.json.getString("external_error_code"))
        assertEquals(VastParsingException::class.java.name, failed.json.getString("exception_class"))
        assertEquals(VastWrapperCleartextException::class.java.name, failed.json.getString("exception_cause_class"))
        assertEquals("nothing reached the server", 0, server.requestCount("/wrapped.xml"))
    }

    /** A wrapper that leads back to itself fails once the chain is deeper than the renderer follows. */
    @Test
    fun aWrapperChainTooDeepReportsItsDepth() {
        server.serve("/loop.xml", "application/xml", wrapperTo(server.url("/loop.xml")).toByteArray())

        val failed = loadFailure(wrapperTo(server.url("/loop.xml")))

        assertEquals("6005", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("parse", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("302", failed.json.getString("external_error_code"))
    }

    /** A wrapper whose response has no ad fails as a wrapped response with no ad that may play. */
    @Test
    fun aWrapperLeadingToNoAdReportsAWrappedResponseWithNoAd() {
        server.serve("/empty.xml", "application/xml", "<VAST version=\"4.2\"></VAST>".toByteArray())

        val failed = loadFailure(wrapperTo(server.url("/empty.xml")))

        assertEquals("6014", failed.json.getString("error_code"))
        assertEquals("creative", failed.json.getString("error_domain"))
        assertEquals("parse", failed.json.getString("failed_stage"))
        assertEquals("vast", failed.json.getString("external_error_source"))
        assertEquals("303", failed.json.getString("external_error_code"))
    }

    /** Loads [vast] into a renderer that is destroyed afterwards, and returns the one failure it reported. */
    private fun loadFailure(vast: String): QueuedEvents.Stored {
        val events = Events()
        val renderer = VastRenderer(instrumentation.targetContext, AdRenderingConfig(), queued.adEvents(Renderer.VAST))
        try {
            instrumentation.runOnMainSync { renderer.load(vast, events) }
            assertTrue("the load did not fail", events.failed.await(20, TimeUnit.SECONDS))

            val failed = queued.next()
            queued.assertAdEvent(failed, "ad_load_failed", EventClass.ERROR, null)
            queued.assertNoMore()
            return failed
        } finally {
            onMain { renderer.destroy() }
        }
    }

    private fun wrapperTo(address: String) = """
        <VAST version="4.2"><Ad id="1"><Wrapper><AdSystem>test</AdSystem>
        <VASTAdTagURI><![CDATA[$address]]></VASTAdTagURI>
        </Wrapper></Ad></VAST>
    """.trimIndent()

    /** A local port nothing listens on, from a socket that was bound and then closed. */
    private fun closedPort(): Int = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /**
     * A renderer destroyed while its VAST is still loading has nothing to report: the cancelled load is not a failure.
     * The wrapper's server never answers, so the load ends only when its read times out, after the destroy.
     */
    @Test
    fun aLoadCancelledByDestroyIsNotAFailure() {
        server.stall("/wrapped.xml")
        val events = Events()
        val renderer = VastRenderer(instrumentation.targetContext, AdRenderingConfig(), queued.adEvents(Renderer.VAST))
        val wrapper = """
            <VAST version="4.2"><Ad id="1"><Wrapper><AdSystem>test</AdSystem>
            <VASTAdTagURI><![CDATA[${server.url("/wrapped.xml")}]]></VASTAdTagURI>
            </Wrapper></Ad></VAST>
        """.trimIndent()
        instrumentation.runOnMainSync { renderer.load(wrapper, events) }
        waitFor("the wrapper request") { (server.requestCount("/wrapped.xml") == 1).takeIf { it } }

        onMain { renderer.destroy() }

        // Past the parser's 10 s read timeout, when the old code reported the cancelled load as an error.
        assertFalse("a cancelled load reported an error", events.failed.await(13, TimeUnit.SECONDS))
        queued.assertNoMore()
    }

    /**
     * A video in a format the device cannot play loads, and fails when it is shown: a show failure (7001, in the
     * `media_select` stage), not a load failure, preceded by the `ad_show_requested` of the call that failed.
     */
    @Test
    fun aVideoWithNoPlayableMediaFileFailsWhenShown() {
        val events = Events()
        val vast = vast("/video.mp4").replace("type=\"video/mp4\"", "type=\"video/x-unplayable\"")

        showAd(vast, events) {
            assertTrue("the show did not fail", events.failed.await(5, TimeUnit.SECONDS))

            queued.assertShowRequested(queued.next())
            val failed = queued.next()
            // The class of what the media file selection failed with.
            queued.assertShowFailed(
                failed,
                ErrorCode.NO_PLAYABLE_MEDIA,
                external = "403" to "vast",
                exceptionClass = "java.lang.IllegalStateException",
            )
            queued.assertNoMore()
            assertTrue(videoScreens().isEmpty())
            assertEquals(0, events.closes.get())
            assertEquals(0, events.impressions.get())
        }
    }

    /** A video that fails before its first frame fails the show: `ad_show_failed`, not `ad_load_failed`. */
    @Test
    fun aVideoThatNeverPlaysCountsNoImpression() {
        server.serve("/video.mp4", "video/mp4", "not a video".toByteArray())
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            assertTrue("the ad did not close", events.closed.await(15, TimeUnit.SECONDS))
            SystemClock.sleep(500)

            assertEquals(0, events.impressions.get())
            queued.assertShowRequested(queued.next())
            val failed = queued.next()
            val playerCode = failed.json.getString("external_error_code")
            assertTrue("no player error code", playerCode.toInt() > 0)
            queued.assertShowFailed(failed, ErrorCode.PLAYER_ERROR, external = playerCode to "media3")
            val exceptionClass = failed.json.getString("exception_class")
            assertTrue("not the player's exception: $exceptionClass", exceptionClass.startsWith("androidx.media3."))
            queued.assertNoMore()
            assertEquals(0, server.requestCount("/impression/dsp"))
            assertEquals(0, server.requestCount("/impression/verifier"))
        }
    }

    /**
     * A playback error after the impression is not a show failure: the show ended in its impression, so no
     * `ad_show_failed` and no `ad_load_failed` follow. The error is made by handing the playing player a media file
     * that answers 404.
     */
    @Test
    fun aPlaybackErrorAfterTheImpressionEmitsNoShowFailure() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        server.respondWith("/gone.mp4", 404)
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            assertTrue(events.impressionReported.await(5, TimeUnit.SECONDS))

            onMain {
                val player = screen.window.decorView.find(PlayerView::class.java)!!.player!!
                player.setMediaItem(MediaItem.fromUri(server.url("/gone.mp4")))
                player.prepare()
            }

            waitFor("the video screen to go") { Unit.takeIf { videoScreens().isEmpty() } }
            SystemClock.sleep(500)
            assertEquals(1, events.impressions.get())
            queued.assertShowRequested(queued.next())
            queued.assertImpression(queued.next())
            queued.assertNoMore()
        }
    }

    @Test
    fun everyClickOnTheVideoSendsTheVideoClickTrackers() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        server.serve("/end-card.png", "image/png", testImage())
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            onMain {
                val video = screen.window.decorView.find(VideoAdView::class.java)!!
                // The layer over the player that takes a tap on the video itself.
                video.find(View::class.java) { it.javaClass == View::class.java && it.isClickable }!!.performClick()
                video.find(TextView::class.java) { it.text == "Learn More" }!!.performClick()
            }
            waitFor("two video click pixels") { (server.requestCount("/click/video") == 2).takeIf { it } }
            SystemClock.sleep(500)

            assertEquals(2, server.requestCount("/click/video"))
            assertEquals(0, server.requestCount("/click/end-card"))
            assertEquals(2, events.clicks.get())
            assertMacrosFilledIn("/click/video")
        }
    }

    @Test
    fun aTapWhileTheVideoIsStillLoadingIsNotAClick() {
        server.stall("/video.mp4")
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            val screen = waitFor("the ad screen") { resumedVideoScreen() }
            onMain {
                val video = screen.window.decorView.find(VideoAdView::class.java)!!
                video.find(View::class.java) { it.javaClass == View::class.java && it.isClickable }!!.performClick()
                assertFalse(
                    "Learn More is offered before the video plays",
                    video.find(TextView::class.java) { it.text == "Learn More" }!!.isShown
                )
            }
            SystemClock.sleep(500)

            assertEquals(0, events.impressions.get())
            assertEquals(0, events.clicks.get())
            assertEquals(0, server.requestCount("/click/video"))
        }
    }

    /**
     * The HTML end card's renderer dies after the video was seen. The screen ends, the ad closes rather than failing,
     * and the death is reported once, as an ad that was shown.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    fun anEndCardWhoseRendererDiesEndsTheScreenAndIsReported() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        val events = Events()
        val vast = vast("/video.mp4").replace(
            Regex("<StaticResource[^>]*>.*?</StaticResource>"),
            "<HTMLResource><![CDATA[<html><body>card</body></html>]]></HTMLResource>",
        )

        showAd(vast, events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            val skip = waitFor("Skip") {
                onMain { screen.window.decorView.find(TextView::class.java) { it.text == "Skip" && it.isShown } }
            }
            onMain { skip.performClick() }
            val card = waitFor("the end card to load") {
                onMain {
                    screen.window.decorView.find(EndCardView::class.java)?.find(WebView::class.java)
                        ?.takeIf { it.progress == 100 }
                }
            }

            onMain { card.loadUrl("chrome://crash") }

            assertTrue("the ad did not close", events.closed.await(30, TimeUnit.SECONDS))
            waitFor("the video screen to go") { Unit.takeIf { videoScreens().isEmpty() } }
            queued.assertShowRequested(queued.next())
            queued.assertImpression(queued.next())
            val gone = queued.next()
            queued.assertAdEvent(gone, "render_crashed", EventClass.ERROR, Renderer.VAST)
            assertEquals("vast", gone.json.getString("renderer"))
            assertTrue(gone.json.getBoolean("did_crash"))
            assertTrue(gone.json.getBoolean("was_shown"))
            assertTrue(
                gone.json.toString(),
                gone.json.getString("renderer_priority_at_exit") in setOf("waived", "bound", "important"),
            )
            assertFalse(gone.json.toString(), gone.json.has("crtype"))
            assertFalse(gone.json.toString(), gone.json.has("creative_type"))
            queued.assertNoMore()
        }
    }

    @Test
    fun aClickOnTheEndCardSendsOnlyTheEndCardClickTrackers() {
        server.serve("/video.mp4", "video/mp4", testVideo())
        server.serve("/end-card.png", "image/png", testImage())
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            val skip = waitFor("Skip") {
                onMain { screen.window.decorView.find(TextView::class.java) { it.text == "Skip" && it.isShown } }
            }
            onMain { skip.performClick() }
            val endCard = waitFor("the end card") { onMain { screen.window.decorView.find(EndCardView::class.java) } }
            onMain {
                endCard.find(ImageView::class.java)!!.performClick()
                endCard.find(TextView::class.java) { it.text == "Learn More" }!!.performClick()
            }
            waitFor("two end card click pixels") { (server.requestCount("/click/end-card") == 2).takeIf { it } }
            SystemClock.sleep(500)

            assertEquals(2, server.requestCount("/click/end-card"))
            assertEquals(0, server.requestCount("/click/video"))
            assertEquals(2, events.clicks.get())
            assertMacrosFilledIn("/click/end-card")
        }
    }

    @Test
    fun aTapWithNoClickThroughIsNotAClick() = assertTapsOpeningNothingAreNotClicks(clickThrough = null)

    @Test
    fun aTapOnAnEmptyClickThroughIsNotAClick() = assertTapsOpeningNothingAreNotClicks(clickThrough = "")

    /** Taps the playing video, then its end card, when neither has anywhere to go. */
    private fun assertTapsOpeningNothingAreNotClicks(clickThrough: String?) {
        server.serve("/video.mp4", "video/mp4", testVideo())
        server.serve("/end-card.png", "image/png", testImage())
        val events = Events()

        showAd(vast("/video.mp4", clickThrough), events) {
            val screen = waitFor("the video to play") { resumedVideoScreen()?.takeIf { it.isPlaying() } }
            onMain {
                screen.window.decorView.find(VideoAdView::class.java)!!
                    .find(View::class.java) { it.javaClass == View::class.java && it.isClickable }!!
                    .performClick()
            }
            val skip = waitFor("Skip") {
                onMain { screen.window.decorView.find(TextView::class.java) { it.text == "Skip" && it.isShown } }
            }
            onMain { skip.performClick() }
            val endCard = waitFor("the end card") { onMain { screen.window.decorView.find(EndCardView::class.java) } }
            onMain { endCard.find(ImageView::class.java)!!.performClick() }
            SystemClock.sleep(1_000)

            assertEquals(0, events.clicks.get())
            assertEquals(0, server.requestCount("/click/video"))
            assertEquals(0, server.requestCount("/click/end-card"))
        }
    }

    /** A ten-second silent clip from the test APK's own assets. */
    private fun testVideo(): ByteArray = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }

    private fun testImage(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun assertMacrosFilledIn(path: String) {
        val sent = server.requestsTo(path)
        assertTrue("nothing was sent to $path", sent.isNotEmpty())
        sent.forEach { assertFalse("a macro was sent unfilled: $it", "[" in it || "%5B" in it.uppercase()) }
    }

    /** Every video ad screen that has not been destroyed yet. */
    private fun videoScreens(): List<VideoAdActivity> = onMain {
        Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filterIsInstance<VideoAdActivity>()
    }

    private fun resumedVideoScreen(): VideoAdActivity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<VideoAdActivity>().firstOrNull()
    }

    private fun VideoAdActivity.isPlaying(): Boolean =
        onMain { window.decorView.find(PlayerView::class.java)?.player?.isPlaying == true }

    /** The first view of [type] in this view's tree, itself included, that [matches]. */
    private fun <T : View> View.find(type: Class<T>, matches: (T) -> Boolean = { true }): T? {
        if (type.isInstance(this) && matches(type.cast(this)!!)) return type.cast(this)
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).find(type, matches)?.let { return it }
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
}
