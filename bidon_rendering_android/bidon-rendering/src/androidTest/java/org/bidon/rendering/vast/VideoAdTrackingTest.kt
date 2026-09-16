package org.bidon.rendering.vast

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import org.bidon.rendering.vast.ui.VideoAdActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A video ad on a real screen, with its video, trackers and advertiser served by a local server.
 *
 * The impression is what the buyer bills on, so it has to mean that the video actually played: it
 * is counted when playback starts, not when the screen opens, and only once for the ad, even when
 * the screen is recreated and the video starts over.
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

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() {
            impressions.incrementAndGet()
            impressionReported.countDown()
        }
        override fun onClick() = Unit
        override fun onClose() = closed.countDown()
        override fun onReward() = Unit
        override fun onError(error: Throwable) = Unit
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun vast(mediaPath: String) = """
        <VAST version="4.2"><Ad id="1"><InLine>
        <AdSystem>test</AdSystem><AdTitle>test</AdTitle>
        <Impression><![CDATA[${server.url("/impression/dsp")}]]></Impression>
        <Impression><![CDATA[${server.url("/impression/verifier")}]]></Impression>
        <Creatives><Creative><Linear>
        <Duration>00:00:10</Duration>
        <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="64" height="64"><![CDATA[${server.url(mediaPath)}]]></MediaFile></MediaFiles>
        </Linear></Creative></Creatives>
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
                instrumentation.runOnMainSync {
                    resumedActivities().filterIsInstance<VideoAdActivity>().forEach(Activity::finish)
                    renderer.destroy()
                }
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
            instrumentation.runOnMainSync { screen.recreate() }
            waitFor("the recreated screen to play") { resumedVideoScreen()?.takeIf { it !== screen && it.isPlaying() } }
            // Pixels go out on a background thread; give a second one time to arrive.
            SystemClock.sleep(1_000)

            assertEquals(1, events.impressions.get())
            assertEquals(1, server.requestCount("/impression/dsp"))
            assertEquals(1, server.requestCount("/impression/verifier"))
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

    /** A ten-second silent clip from the test APK's own assets. */
    private fun testVideo(): ByteArray = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }

    private fun resumedActivities(): Collection<Activity> =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)

    private fun resumedVideoScreen(): VideoAdActivity? {
        var screen: VideoAdActivity? = null
        instrumentation.runOnMainSync {
            screen = resumedActivities().filterIsInstance<VideoAdActivity>().firstOrNull()
        }
        return screen
    }

    private fun VideoAdActivity.isPlaying(): Boolean {
        var playing = false
        instrumentation.runOnMainSync {
            playing = window.decorView.findPlayerView()?.player?.isPlaying == true
        }
        return playing
    }

    private fun View.findPlayerView(): PlayerView? = when (this) {
        is PlayerView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findPlayerView() }
        else -> null
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
