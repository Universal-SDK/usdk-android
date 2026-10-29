package org.bidon.rendering.mraid

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.TestCreatives.PAINTED
import org.bidon.rendering.TestCreatives.SETTLE_MILLIS
import org.bidon.rendering.tap
import org.bidon.rendering.telemetry.Renderer
import org.bidon.rendering.telemetry.QueuedEvents
import org.bidon.rendering.ui.BlankActivity
import org.bidon.rendering.ui.CloseButton
import org.bidon.rendering.waitUntil
import org.bidon.rendering.whileTheDeviceIsRotated
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An MRAID banner the host placed on its screen expands to fill the screen, with the SDK's close button, and every
 * way out of the expanded state puts it back where the host placed it. The host is a plain [android.app.Activity],
 * so nothing here relies on the host being a `ComponentActivity`.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class MraidExpandTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val loaded = CountDownLatch(1)
    private val clicks = AtomicInteger()

    private val directory = File(instrumentation.targetContext.cacheDir, "mraid-expand-${SystemClock.elapsedRealtimeNanos()}")
    private val queued by lazy { QueuedEvents(directory) }
    private val renderer by lazy {
        MraidRenderer(instrumentation.targetContext, AdRenderingConfig(), queued.adEvents(Renderer.MRAID))
    }
    private lateinit var scenario: ActivityScenario<BlankActivity>
    private lateinit var container: FrameLayout

    private val banner: WebView get() = renderer.getView() as WebView

    @Before
    fun placeBanner() {
        val listener = object : AdRenderer.Listener {
            override fun onLoaded() = loaded.countDown()
            override fun onImpression() = Unit
            override fun onClick() {
                clicks.incrementAndGet()
            }
            override fun onClose() = Unit
            override fun onReward() = Unit
            override fun onError(error: Throwable) = Unit
        }
        instrumentation.runOnMainSync { renderer.load(PAINTED + STATES, listener) }
        assertTrue("the ad did not load", loaded.await(10, TimeUnit.SECONDS))
        scenario = ActivityScenario.launch(BlankActivity::class.java)
        scenario.onActivity { activity ->
            container = FrameLayout(activity)
            // Not the size an expanded banner takes, so putting it back can be told apart from leaving it stretched.
            container.addView(banner, FrameLayout.LayoutParams(BANNER_WIDTH, BANNER_HEIGHT))
            activity.setContentView(container)
        }
        SystemClock.sleep(SETTLE_MILLIS)
        run("events.length = 0")
        queued.assertImpression(queued.next())
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { renderer.destroy() }
        if (::scenario.isInitialized) scenario.close()
        directory.deleteRecursively()
    }

    @Test
    fun anInlineBannerExpandsToFillTheScreenWithACloseButton() {
        expand()

        assertEquals("\"expanded\"", run("events.join()"))
        assertEquals("\"expanded\"", run("mraid.getState()"))
        instrumentation.runOnMainSync {
            assertNotSame("the banner is still on the host's screen", container.rootView, banner.rootView)
            assertEquals(banner.rootView.width, banner.width)
            assertEquals(banner.rootView.height, banner.height)
            assertEquals(container.rootView.width, banner.rootView.width)
            val close = closeButton()
            assertNotNull("no close button", close)
            assertTrue("the close button is not shown", close!!.isShown)
            assertTrue("the close button cannot be used yet", close.isEnabled)
        }
        assertEquals("expanding is not a click", 0, clicks.get())
        assertEquals("the host was paused", Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun theCloseButtonPutsTheBannerBack() {
        expand()

        tapOnScreen { closeButton()!! }
        SystemClock.sleep(SETTLE_MILLIS)

        assertBackInItsContainer()
    }

    @Test
    fun backPutsTheBannerBackAndLeavesTheHostOpen() {
        expand()

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(SETTLE_MILLIS)

        assertBackInItsContainer()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        // Back is the user closing it, as the close button is.
        expand()
        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
    }

    @Test
    fun theCreativeClosingItselfPutsTheBannerBack() {
        expand()

        run("mraid.close()")
        SystemClock.sleep(SETTLE_MILLIS)

        assertBackInItsContainer()

        // A creative closing itself holds the next expand as the user closing it does, or it could expand at once.
        expand()
        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
    }

    /** A creative that expands again as soon as it is closed: the close holds until the user touches the ad. */
    @Test
    fun afterTheUserClosesItTheAdExpandsAgainOnlyAfterATouch() {
        run("mraid.addEventListener('stateChange', function (state) { if (state === 'default') mraid.expand() })")
        expand()

        tapOnScreen { closeButton()!! }
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
        assertStillInline()

        instrumentation.runOnMainSync { banner.tap() }
        expand()

        assertEquals("\"expanded,default,error expand,expanded\"", run("events.join()"))
    }

    // A key that activates what has focus in the ad counts as the touch, for the user without a touch screen.

    @Test
    fun afterAClosePressingEnterOnTheAdLetsItExpandAgain() = assertPressingLetsItExpandAgain(KeyEvent.KEYCODE_ENTER)

    @Test
    fun afterAClosePressingNumpadEnterOnTheAdLetsItExpandAgain() =
        assertPressingLetsItExpandAgain(KeyEvent.KEYCODE_NUMPAD_ENTER)

    @Test
    fun afterAClosePressingTheDpadCentreOnTheAdLetsItExpandAgain() =
        assertPressingLetsItExpandAgain(KeyEvent.KEYCODE_DPAD_CENTER)

    @Test
    fun afterAClosePressingSpaceOnTheAdLetsItExpandAgain() = assertPressingLetsItExpandAgain(KeyEvent.KEYCODE_SPACE)

    @Test
    fun afterAClosePressingALetterOnTheAdDoesNotLetItExpandAgain() {
        closeAndFocusTheBanner()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
        expand()

        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
    }

    // A key held down or let go after the close is not a new press. A press that closes the ad through a control in the
    // creative lets go after the close, and a held key repeats.

    @Test
    fun afterACloseAKeyHeldDownDoesNotLetItExpandAgain() {
        closeAndFocusTheBanner()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1))
        expand()

        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
    }

    @Test
    fun afterACloseAKeyLetGoDoesNotLetItExpandAgain() {
        closeAndFocusTheBanner()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        expand()

        assertEquals("\"expanded,default,error expand\"", run("events.join()"))
    }

    /** A host that shows the banner in a dialog of its own, a bottom sheet for instance. */
    @Test
    fun aBannerInTheHostsDialogExpandsOverIt() {
        lateinit var hostDialog: Dialog
        scenario.onActivity { activity ->
            (container.parent as ViewGroup).removeView(container)
            hostDialog = Dialog(activity).apply {
                setContentView(container)
                show()
            }
        }
        try {
            SystemClock.sleep(SETTLE_MILLIS)

            expand()

            assertEquals("\"expanded\"", run("events.join()"))
            instrumentation.runOnMainSync {
                assertNotSame(container.rootView, banner.rootView)
                assertTrue("the expanded ad is not on screen", banner.isShown)
            }
        } finally {
            instrumentation.runOnMainSync { hostDialog.dismiss() }
        }
    }

    @Test
    fun destroyingTheAdWhileExpandedTakesTheExpandedAdDown() {
        expand()
        var expandedRoot: View? = null

        instrumentation.runOnMainSync {
            expandedRoot = banner.rootView
            renderer.destroy()
        }
        SystemClock.sleep(SETTLE_MILLIS)

        instrumentation.runOnMainSync {
            assertTrue("the expanded ad is still on screen", !expandedRoot!!.isAttachedToWindow)
            assertEquals("a destroyed banner was put back", 0, container.childCount)
        }
    }

    @Test
    fun theHostTakingTheBannerOffItsScreenTakesTheExpandedAdDown() {
        expand()

        scenario.onActivity { it.setContentView(FrameLayout(it)) }
        SystemClock.sleep(SETTLE_MILLIS)

        instrumentation.runOnMainSync {
            assertSame(container, banner.parent)
            assertTrue("the banner is still on a screen", !banner.isAttachedToWindow)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    /**
     * A container the host made on one screen and moved to the next, after the first one closed: the ad expands over
     * the screen it is on. A dialog on the closed screen would crash the host.
     */
    @Test
    fun aBannerMovedToAnotherScreenExpandsOverTheScreenItIsOn() {
        // Launching the next screen closes the first one, the one the container was made on.
        ActivityScenario.launch(BlankActivity::class.java).use { next ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            next.onActivity { activity ->
                (container.parent as ViewGroup).removeView(container)
                activity.setContentView(FrameLayout(activity).also { it.addView(container) })
            }
            SystemClock.sleep(SETTLE_MILLIS)

            expand()

            assertEquals("\"expanded\"", run("events.join()"))
            instrumentation.runOnMainSync {
                assertNotSame(container.rootView, banner.rootView)
                assertTrue("the expanded ad is not on screen", banner.isShown)
            }
        }
    }

    @Test
    fun aRecreatedHostLeavesNoExpandedAdAndABannerThatExpandsAgain() {
        expand()

        scenario.recreate()
        SystemClock.sleep(SETTLE_MILLIS)

        assertCollapsedAfterTheHostWentAway()
    }

    /**
     * The same, with the first screen still open behind the next one: a dialog on it would be on no screen the user
     * sees, so the ad expands over the screen the banner is on.
     */
    @Test
    fun aBannerMovedFromAScreenStillOpenExpandsOverTheScreenItIsOn() {
        scenario.onActivity { it.startActivity(Intent(it, BlankActivity::class.java)) }
        val next = waitForActivity { it !== activityOf(scenario) }
        try {
            instrumentation.runOnMainSync {
                (container.parent as ViewGroup).removeView(container)
                next.setContentView(FrameLayout(next).also { it.addView(container) })
            }
            assertNotEquals("the first screen closed", Lifecycle.State.DESTROYED, scenario.state)
            SystemClock.sleep(SETTLE_MILLIS)

            expand()

            assertEquals("\"expanded\"", run("events.join()"))
            instrumentation.runOnMainSync {
                val expandedWindow = banner.rootView.layoutParams as WindowManager.LayoutParams
                assertSame("the ad expanded over the screen behind", next.window.attributes.token, expandedWindow.token)
            }
        } finally {
            instrumentation.runOnMainSync { next.finish() }
        }
    }

    /** A host that keeps the banner's container across a recreation, and puts it on the new screen. */
    @Test
    fun aContainerKeptAcrossARecreationExpandsOverTheNewScreen() {
        scenario.recreate()
        lateinit var current: Activity
        scenario.onActivity { activity ->
            current = activity
            (container.parent as ViewGroup).removeView(container)
            activity.setContentView(FrameLayout(activity).also { it.addView(container) })
        }
        SystemClock.sleep(SETTLE_MILLIS)

        expand()

        assertEquals("\"expanded\"", run("events.join()"))
        instrumentation.runOnMainSync {
            assertSame("the ad expanded on the screen that was replaced", current, closeButton()!!.context)
        }
    }

    @Test
    fun aRotationLeavesNoExpandedAdAndABannerThatExpandsAgain() {
        expand()
        instrumentation.whileTheDeviceIsRotated {
            instrumentation.waitUntil("the host was not recreated") { !banner.isAttachedToWindow }

            assertCollapsedAfterTheHostWentAway()
        }
    }

    /** Expanding and closing moves the one banner between windows, which is not a second sight of it. */
    @Test
    fun expandingAndClosingTheBannerCountsNoSecondImpression() {
        expand()
        tapOnScreen { closeButton()!! }
        SystemClock.sleep(SETTLE_MILLIS)

        assertBackInItsContainer()
        instrumentation.runOnMainSync { banner.tap() }
        expand()
        run("mraid.close()")
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"expanded,default,expanded,default\"", run("events.join()"))
        queued.assertNoMore()
    }

    @Test
    fun aRecreatedHostWithAnExpandedBannerCountsNoSecondImpression() {
        expand()

        scenario.recreate()
        SystemClock.sleep(SETTLE_MILLIS)
        assertCollapsedAfterTheHostWentAway()

        queued.assertNoMore()
    }

    @Test
    fun aRotatedHostWithAnExpandedBannerCountsNoSecondImpression() {
        expand()

        instrumentation.whileTheDeviceIsRotated {
            instrumentation.waitUntil("the host was not recreated") { !banner.isAttachedToWindow }
            assertCollapsedAfterTheHostWentAway()

            queued.assertNoMore()
        }
    }

    @Test
    fun aTwoPartExpandFiresAnErrorAndChangesNothing() {
        val page = pageUrl()

        run("mraid.expand('https://advertiser.example/landing')")
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"error expand\"", run("events.join()"))
        assertStillInline()
        assertEquals(page, pageUrl())
    }

    /** A creative that calls the bridge itself, past `mraid.js`, cannot hand it a page either. */
    @Test
    fun theBridgeTakesNoPageToExpandTo() {
        val page = pageUrl()

        val result = run(
            "typeof _mraidBridge.expand + ' ' + (function () { try {" +
                " _mraidBridge.expand('https://advertiser.example/landing'); return 'called' }" +
                " catch (e) { return 'refused' } })()"
        )
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"function refused\"", result)
        assertStillInline()
        assertEquals(page, pageUrl())
    }

    /** The creative hears it once the banner is on a screen again: until then no MRAID event reaches it. */
    @Test
    fun aBannerOnNoScreenFiresAnErrorOnExpand() {
        instrumentation.runOnMainSync { container.removeView(banner) }

        expand()
        instrumentation.runOnMainSync { container.addView(banner) }
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"error expand\"", run("events.join()"))
        assertStillInline()
    }

    @Test
    fun aHiddenBannerFiresAnErrorOnExpand() {
        instrumentation.runOnMainSync { container.visibility = View.INVISIBLE }

        expand()

        assertEquals("\"error expand\"", run("events.join()"))
        assertStillInline()
    }

    @Test
    fun aFullscreenAdFiresAnErrorOnExpand() {
        scenario.onActivity { renderer.showFullscreen(it) }
        SystemClock.sleep(SETTLE_MILLIS)
        try {
            expand()

            assertEquals("\"error expand\"", run("events.join()"))
            assertEquals("\"default\"", run("mraid.getState()"))
        } finally {
            // Ends the fullscreen screen, so it does not stay up over the next test.
            run("mraid.close()")
            SystemClock.sleep(SETTLE_MILLIS)
        }
    }

    private fun expand() {
        run("mraid.expand()")
        SystemClock.sleep(SETTLE_MILLIS)
    }

    private fun assertPressingLetsItExpandAgain(keyCode: Int) {
        closeAndFocusTheBanner()
        // A creative that handles the key itself. Otherwise Android follows a key the ad leaves alone with its fallback,
        // Enter for numpad Enter, and the test would pass on Enter.
        run("document.addEventListener('keydown', function (e) { e.preventDefault() })")
        instrumentation.sendKeyDownUpSync(keyCode)
        expand()

        assertEquals("\"expanded,default,expanded\"", run("events.join()"))
    }

    /** Expanded and closed, which holds the next expand, with the keys going to the banner. */
    private fun closeAndFocusTheBanner() {
        expand()
        run("mraid.close()")
        SystemClock.sleep(SETTLE_MILLIS)
        instrumentation.runOnMainSync { banner.requestFocus() }
        instrumentation.waitUntil("the banner did not take the focus") { banner.hasFocus() }
    }

    private fun assertBackInItsContainer() {
        assertEquals("\"expanded,default\"", run("events.join()"))
        assertEquals("\"default\"", run("mraid.getState()"))
        instrumentation.runOnMainSync {
            assertSame(container, banner.parent)
            assertEquals(BANNER_WIDTH, banner.layoutParams.width)
            assertEquals(BANNER_HEIGHT, banner.layoutParams.height)
            assertSame(container.rootView, banner.rootView)
        }
    }

    private fun assertStillInline() {
        assertEquals("\"default\"", run("mraid.getState()"))
        instrumentation.runOnMainSync {
            assertSame(container, banner.parent)
            assertSame(container.rootView, banner.rootView)
        }
    }

    /**
     * The host's screen was destroyed while the ad was expanded: the expanded ad went with it, and the banner is back
     * in its old container, on no screen. A host that places it on its new screen finds it in `default`, and can
     * expand it again. The creative hears `default` once the banner is on a screen again.
     */
    private fun assertCollapsedAfterTheHostWentAway() {
        instrumentation.runOnMainSync {
            assertSame(container, banner.parent)
            assertTrue("the banner is still on a screen", !banner.isAttachedToWindow)
        }

        scenario.onActivity { activity ->
            container.removeView(banner)
            activity.setContentView(FrameLayout(activity).also { it.addView(banner) })
        }
        SystemClock.sleep(SETTLE_MILLIS)

        assertEquals("\"expanded,default\"", run("events.join()"))
        assertEquals("\"default\"", run("mraid.getState()"))

        instrumentation.runOnMainSync { banner.tap() }
        expand()

        assertEquals("\"expanded,default,expanded\"", run("events.join()"))
        instrumentation.runOnMainSync { assertNotSame(container.rootView, banner.rootView) }
    }

    /** A finger down and up in the middle of the view as it is on screen, in whichever window is on top there. */
    private fun tapOnScreen(view: () -> View) {
        val location = IntArray(2)
        var size = 0 to 0
        instrumentation.runOnMainSync {
            val target = view()
            target.getLocationOnScreen(location)
            size = target.width to target.height
        }
        val x = location[0] + size.first / 2f
        val y = location[1] + size.second / 2f
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    private fun activityOf(scenario: ActivityScenario<BlankActivity>): Activity {
        lateinit var activity: Activity
        scenario.onActivity { activity = it }
        return activity
    }

    /** The resumed activity that [matches], once there is one. */
    private fun waitForActivity(matches: (Activity) -> Boolean): Activity {
        var found: Activity? = null
        instrumentation.waitUntil("no such activity was resumed") {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull(matches)
            found != null
        }
        return found!!
    }

    private fun pageUrl(): String? {
        var url: String? = null
        instrumentation.runOnMainSync { url = banner.url }
        return url
    }

    private fun closeButton(): CloseButton? = banner.rootView.findCloseButton()

    private fun View.findCloseButton(): CloseButton? {
        if (this is CloseButton) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findCloseButton()?.let { return it }
        return null
    }

    private fun run(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            banner.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue("the script did not finish", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        const val BANNER_WIDTH = 320
        const val BANNER_HEIGHT = 50

        /** Records every state change and error the creative hears, in order. */
        const val STATES = "<script>window.events = [];" +
            "mraid.addEventListener('stateChange', function (state) { events.push(state) });" +
            "mraid.addEventListener('error', function (message, action) { events.push('error ' + action) })</script>"
    }
}
