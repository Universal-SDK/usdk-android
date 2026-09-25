package org.bidon.rendering.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import java.util.Collections
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

        /** Impressions and rewards in the order they arrived. */
        val order: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() {
            order += "impression"
        }
        override fun onClick() = Unit
        override fun onClose() = closed.countDown()
        override fun onReward() {
            order += "reward"
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

    @Test
    fun htmlDoesNotRewardAnAdClosedJustBeforeTheCloseButton() = doesNotRewardAnAdClosedJustBeforeTheCloseButton(Kind.HTML)

    @Test
    fun mraidDoesNotRewardAnAdClosedJustBeforeTheCloseButton() =
        doesNotRewardAnAdClosedJustBeforeTheCloseButton(Kind.MRAID)

    @Test
    fun htmlRewardsOnceWhenRecreatedAfterEarningInTheBackground() =
        rewardsOnceWhenRecreatedAfterEarningInTheBackground(Kind.HTML)

    @Test
    fun mraidRewardsOnceWhenRecreatedAfterEarningInTheBackground() =
        rewardsOnceWhenRecreatedAfterEarningInTheBackground(Kind.MRAID)

    @Test
    fun htmlRewardsAfterTheImpressionWhenThereIsNoDelay() = rewardsAfterTheImpressionWhenThereIsNoDelay(Kind.HTML)

    @Test
    fun mraidRewardsAfterTheImpressionWhenThereIsNoDelay() = rewardsAfterTheImpressionWhenThereIsNoDelay(Kind.MRAID)

    private fun rewardsWhenTheCloseButtonBecomesAvailable(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 2, events) { shownAt ->
            val screen = waitFor("the ad screen") { resumedAdScreen() }
            sleepUntil(screen.closeDeadline() - 300)
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
            val recreatedAt = SystemClock.elapsedRealtime()
            onMain { screen.recreate() }
            waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== screen } }

            assertTrue("no reward", events.rewarded.await(5, TimeUnit.SECONDS))
            val rewardedAt = SystemClock.elapsedRealtime()
            assertTrue("rewarded before the 3 s delay", rewardedAt - shownAt >= 3_000)
            // The countdown carries on across recreation. Started over, it would end 3 s after the recreation; carried
            // on, it ends at most 2 s after it, since the screen had been up for at least 1 s.
            assertTrue(
                "rewarded ${rewardedAt - recreatedAt} ms after the recreation",
                rewardedAt - recreatedAt < 2_500,
            )
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
     * The countdown keeps running after finish() until the screen is destroyed. The main thread is held from just
     * before the deadline to just after it, and finish() is called inside that hold: the countdown then fires on a
     * screen that is already finishing, before it can be destroyed, which takes further trips through the main thread.
     */
    private fun doesNotRewardAnAdClosedJustBeforeTheCloseButton(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 2, events) {
            val screen = waitFor("the ad screen") { resumedAdScreen() }
            val deadline = screen.closeDeadline()
            sleepUntil(deadline - 200)
            onMain {
                screen.finish()
                sleepUntil(deadline + 100)
            }
            assertTrue("the ad did not close", events.closed.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            assertEquals(0, events.rewards.get())
        }
    }

    /**
     * The saved state of a stopped activity is written when it stops, before a reward earned in the background. A
     * configuration change while it is stopped recreates it from that older state when the user returns.
     */
    private fun rewardsOnceWhenRecreatedAfterEarningInTheBackground(kind: Kind) {
        val events = Events()
        val nightMode = shell("cmd uimode night").trim()
        try {
            showAd(kind, closeDelaySeconds = 3, events) {
                val screen = waitFor("the ad screen") { resumedAdScreen() }
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                waitFor("the ad screen to stop") { stoppedAdScreen()?.takeIf { it === screen } }

                assertTrue("no reward in the background", events.rewarded.await(5, TimeUnit.SECONDS))
                shell(if (nightMode.endsWith("yes")) "cmd uimode night no" else "cmd uimode night yes")

                val activityManager = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
                onMain { activityManager.moveTaskToFront(screen.taskId, 0) }
                waitFor("the recreated screen") { resumedAdScreen()?.takeIf { it !== screen } }
                SystemClock.sleep(1_000)
                assertEquals(1, events.rewards.get())
            }
        } finally {
            shell("cmd uimode night ${nightMode.substringAfterLast(' ')}")
        }
    }

    private fun rewardsAfterTheImpressionWhenThereIsNoDelay(kind: Kind) {
        val events = Events()
        showAd(kind, closeDelaySeconds = 0, events) {
            assertTrue("no reward", events.rewarded.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("impression", "reward"), events.order.toList())
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

    /** When [this] screen's close button becomes available, as an [SystemClock.elapsedRealtime] time. */
    private fun Activity.closeDeadline(): Long = onMain {
        Bundle().also { instrumentation.callActivityOnSaveInstanceState(this, it) }.closeAvailableAtMs()!!
    }

    private fun sleepUntil(elapsedRealtimeMs: Long) {
        val remaining = elapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
    }

    private fun stoppedAdScreen(): Activity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).firstOrNull(::isAdScreen)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

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
