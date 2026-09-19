package org.bidon.rendering.vast

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.LocalHttpServer
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

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val impressionReported = CountDownLatch(1)
        val impressions = AtomicInteger()
        val clicks = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() {
            impressions.incrementAndGet()
            impressionReported.countDown()
        }
        override fun onClick() {
            clicks.incrementAndGet()
        }
        override fun onClose() = closed.countDown()
        override fun onReward() = Unit
        override fun onError(error: Throwable) = Unit
    }

    @After
    fun stopServer() {
        server.close()
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
        val renderer = VastRenderer(instrumentation.targetContext, AdRenderingConfig())
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
            assertEquals(1, server.requestCount("/impression/dsp"))
            assertEquals(1, server.requestCount("/impression/verifier"))
            assertMacrosFilledIn("/impression/dsp")
        }
    }

    @Test
    fun aVideoThatNeverPlaysCountsNoImpression() {
        server.serve("/video.mp4", "video/mp4", "not a video".toByteArray())
        val events = Events()

        showAd(vast("/video.mp4"), events) {
            assertTrue("the ad did not close", events.closed.await(15, TimeUnit.SECONDS))
            SystemClock.sleep(500)

            assertEquals(0, events.impressions.get())
            assertEquals(0, server.requestCount("/impression/dsp"))
            assertEquals(0, server.requestCount("/impression/verifier"))
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
