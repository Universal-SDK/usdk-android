package org.bidon.rendering.ui

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.html.HtmlRenderer
import org.bidon.rendering.mraid.MraidRenderer
import org.bidon.rendering.mraid.ui.MraidAdActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A display or playable ad has no completion, so the reward is earned when the user could close the ad: the moment
 * the close button becomes available. It is reported once for the ad, however often the screen is recreated, and not
 * at all when the ad goes away before that moment. Checked for both the HTML and the MRAID screen.
 */
@RunWith(AndroidJUnit4::class)
class DisplayRewardTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Events : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val rewarded = CountDownLatch(1)
        val rewards = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() = Unit
        override fun onClick() = Unit
        override fun onClose() = closed.countDown()
        override fun onReward() {
            rewards.incrementAndGet()
            rewarded.countDown()
        }
        override fun onError(error: Throwable) = Unit
    }

    private enum class Kind { HTML, MRAID }

    @Test
    fun htmlRewardsWhenTheCloseButtonBecomesAvailable() = rewardsWhenTheCloseButtonBecomesAvailable(Kind.HTML)

    @Test
    fun mraidRewardsWhenTheCloseButtonBecomesAvailable() = rewardsWhenTheCloseButtonBecomesAvailable(Kind.MRAID)

    @Test
    fun htmlRewardsOnceWhenRecreatedMidCountdown() = rewardsOnceWhenRecreatedMidCountdown(Kind.HTML)

    @Test
    fun mraidRewardsOnceWhenRecreatedMidCountdown() = rewardsOnceWhenRecreatedMidCountdown(Kind.MRAID)

    @Test
    fun htmlDoesNotRewardAnAdClosedEarly() = doesNotRewardAnAdClosedEarly(Kind.HTML)

    @Test
    fun mraidDoesNotRewardAnAdClosedEarly() = doesNotRewardAnAdClosedEarly(Kind.MRAID)

    private fun rewardsWhenTheCloseButtonBecomesAvailable(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 2, events) { shownAt ->
            val screen = waitFor("the ad screen") { resumedAdScreen() }
            SystemClock.sleep(1_000)
            assertEquals("rewarded before the close button was available", 0, events.rewards.get())
            assertFalse(onMain { screen.closeButton().isEnabled })

            assertTrue("no reward", events.rewarded.await(5, TimeUnit.SECONDS))
            val rewardedAfterMs = SystemClock.elapsedRealtime() - shownAt
            assertTrue("rewarded after $rewardedAfterMs ms, before the 2 s delay", rewardedAfterMs >= 2_000)
            assertTrue(onMain { screen.closeButton().isEnabled })

            // A theme or language change recreates the screen with the close button already available.
            onMain { screen.recreate() }
            waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== screen } }
            SystemClock.sleep(1_000)
            assertEquals(1, events.rewards.get())
        }
    }

    private fun rewardsOnceWhenRecreatedMidCountdown(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 3, events) { shownAt ->
            val screen = waitFor("the ad screen") { resumedAdScreen() }
            SystemClock.sleep(1_000)
            onMain { screen.recreate() }
            waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== screen } }

            assertTrue("no reward", events.rewarded.await(5, TimeUnit.SECONDS))
            val rewardedAfterMs = SystemClock.elapsedRealtime() - shownAt
            // The countdown carries on across recreation rather than starting over, and does not end early.
            assertTrue("rewarded after $rewardedAfterMs ms", rewardedAfterMs in 3_000 until 4_000)
            SystemClock.sleep(1_000)
            assertEquals(1, events.rewards.get())
        }
    }

    private fun doesNotRewardAnAdClosedEarly(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 3, events) {
            val screen = waitFor("the ad screen") { resumedAdScreen() }
            // The creative closing itself (mraid.close) or the host tearing the screen down.
            onMain { screen.finish() }
            assertTrue("the ad did not close", events.closed.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(3_500)
            assertEquals(0, events.rewards.get())
        }
    }

    /**
     * Loads a creative of [kind] whose close button waits [closeDelaySeconds], shows it over a blank screen, runs
     * [block] with the moment it was shown, and tears everything down.
     */
    private fun showAd(kind: Kind, closeDelaySeconds: Int, events: Events, block: (shownAt: Long) -> Unit) {
        val context = instrumentation.targetContext
        val rendering = AdRenderingConfig.fromJson(
            JSONObject("""{"close_button":{"delay_seconds":$closeDelaySeconds}}""")
        )
        val renderer = when (kind) {
            Kind.HTML -> HtmlRenderer(context, rendering)
            Kind.MRAID -> MraidRenderer(context, rendering)
        }
        val markup = when (kind) {
            Kind.HTML -> "<html><body><p>ad</p></body></html>"
            Kind.MRAID -> "<html><body><script src=\"mraid.js\"></script><p>ad</p></body></html>"
        }
        instrumentation.runOnMainSync { renderer.load(markup, events) }
        assertTrue("the ad did not load", events.loaded.await(10, TimeUnit.SECONDS))
        ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            try {
                var shownAt = 0L
                scenario.onActivity {
                    shownAt = SystemClock.elapsedRealtime()
                    renderer.showFullscreen(it)
                }
                block(shownAt)
            } finally {
                val screens = adScreens()
                onMain { screens.forEach(Activity::finish) }
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (adScreens().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                onMain { renderer.destroy() }
            }
        }
    }

    private fun isAdScreen(activity: Activity) = activity is FullscreenAdActivity || activity is MraidAdActivity

    private fun adScreens(): List<Activity> = onMain {
        Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            .filter(::isAdScreen)
    }

    private fun resumedAdScreen(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull(::isAdScreen)
    }

    private fun Activity.closeButton(): CloseButton = window.decorView.find(CloseButton::class.java)!!

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
}
