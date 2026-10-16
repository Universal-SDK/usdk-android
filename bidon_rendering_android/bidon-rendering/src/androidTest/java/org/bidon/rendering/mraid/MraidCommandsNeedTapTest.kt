package org.bidon.rendering.mraid

import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.LinkStarts
import org.bidon.rendering.TestCreatives.APP_LINK
import org.bidon.rendering.TestCreatives.FILL
import org.bidon.rendering.TestCreatives.LINKS
import org.bidon.rendering.TestCreatives.OPENED
import org.bidon.rendering.TestCreatives.OTHER_APP_LINK
import org.bidon.rendering.TestCreatives.PAINTED
import org.bidon.rendering.TestCreatives.SETTLE_MILLIS
import org.bidon.rendering.swipe
import org.bidon.rendering.tap
import org.bidon.rendering.ui.BlankActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MRAID commands that leave the ad open their link as a navigation, so they follow the rule every link in a
 * creative does: only after a tap, one link for each tap. Each creative is an MRAID banner the host placed on its
 * screen, tapped the way a finger taps it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class MraidCommandsNeedTapTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Clicks : AdRenderer.Listener {
        val loaded = CountDownLatch(1)
        val count = AtomicInteger()

        override fun onLoaded() = loaded.countDown()
        override fun onImpression() = Unit
        override fun onClick() {
            count.incrementAndGet()
        }
        override fun onClose() = Unit
        override fun onReward() = Unit
        override fun onError(error: Throwable) = Unit
    }

    private class Shown(val links: List<String>, val clicks: Int, val scriptResult: String?)

    /**
     * Shows [body] as an MRAID banner filling the host's screen, lets it run, does [touch] to it if given, and returns
     * what it opened, with what [script] then evaluates to in the creative.
     */
    private fun show(body: String, touch: (WebView.() -> Unit)? = null, script: String? = null): Shown {
        val listener = Clicks()
        val renderer = MraidRenderer(instrumentation.targetContext, AdRenderingConfig(), events = null)
        var scriptResult: String? = null
        val links = ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            LinkStarts.during {
                try {
                    instrumentation.runOnMainSync { renderer.load("$PAINTED$FILL$body", listener) }
                    assertTrue("the ad did not load", listener.loaded.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        activity.setContentView(FrameLayout(activity).also { renderer.attach(it) })
                    }
                    val banner = renderer.getView() as WebView
                    SystemClock.sleep(SETTLE_MILLIS)
                    if (touch != null) {
                        instrumentation.runOnMainSync { banner.touch() }
                        SystemClock.sleep(SETTLE_MILLIS)
                    }
                    if (script != null) scriptResult = evaluate(banner, script)
                } finally {
                    instrumentation.runOnMainSync { renderer.destroy() }
                }
            }
        }
        return Shown(links, listener.count.get(), scriptResult)
    }

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue("the script did not finish", done.await(5, TimeUnit.SECONDS))
        return result
    }

    /** The creative also hears why nothing opened. */
    @Test
    fun aCreativeThatOpensALinkAsItLoadsOpensNothing() {
        val shown = show(
            "$ERRORS<script>" +
                "mraid.open('$APP_LINK'); mraid.playVideo('$APP_LINK'); mraid.storePicture('$APP_LINK')</script>",
            script = "errors.join()",
        )

        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
        assertEquals("\"open,playVideo,storePicture\"", shown.scriptResult)
    }

    /** The bridge no longer takes a link: what leaves the ad goes through the WebView, as any navigation does. */
    @Test
    fun aCreativeThatCallsTheBridgeDirectlyOpensNothing() {
        val shown = show(
            "<script>['open', 'playVideo', 'storePicture', 'createCalendarEvent'].forEach(function (name) {" +
                "try { window._mraidBridge[name]('$APP_LINK', '') } catch (e) {} })</script>",
            touch = { tap() },
        )

        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    /**
     * After a tap, each opens its link through the rules every link in a creative follows: a screen of the host app
     * that is not exported stays out of reach. A tap is a click even when its link does not open.
     */
    @Test
    fun eachCommandThatLeavesTheAdOpensItsLinkAfterATap() {
        for (command in listOf("open", "playVideo", "storePicture")) {
            val shown = LINKS.map { link ->
                show("<div id='ad' onclick=\"mraid.$command('$link')\"></div>", touch = { tap() })
            }

            assertEquals(command, OPENED, shown.flatMap { it.links })
            assertEquals(command, LINKS.size, shown.sumOf { it.clicks })
        }
    }

    /**
     * The WebView hands a tap to the first navigation that follows it and to no other; the ad keeps no count of its
     * own, so this shows the WebView's rule, measured on WebView 124.
     */
    @Test
    fun oneTapOpensOneLink() {
        val shown = show(
            "<div id='ad' onclick=\"mraid.open('$APP_LINK'); mraid.open('$OTHER_APP_LINK')\"></div>",
            touch = { tap() },
        )

        assertEquals(listOf(APP_LINK), shown.links)
        assertEquals(1, shown.clicks)
    }

    /**
     * A swipe fires no click, so a link opened from one stays shut. Whether a creative that opens its link when the
     * finger lifts can still open it at the end of a swipe is the WebView's call, as in a browser.
     */
    @Test
    fun aSwipeAcrossTheAdOpensNothing() {
        val shown = show("<div id='ad' onclick=\"mraid.open('$APP_LINK')\"></div>", touch = { swipe() })

        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    /** MRAID 3 dropped calendar events; the creative hears that the command failed, as the standard asks. */
    @Test
    fun aCalendarEventIsRefusedWithAnError() {
        val shown = show(
            "$ERRORS<div id='ad' " +
                "onclick=\"mraid.createCalendarEvent({ description: 'Launch', start: '2026-10-14' })\"></div>",
            touch = { tap() },
            script = "errors.join()",
        )

        assertEquals(emptyList<String>(), shown.links)
        assertEquals("\"createCalendarEvent\"", shown.scriptResult)
    }

    /** Each would replace the creative with a page of its own, and is not a link. */
    @Test
    fun theCreativesOwnPageIsNotOpenedAsALink() {
        val shown = show(
            "$ERRORS<div id='ad' onclick=\"mraid.open('about:blank'); mraid.open('javascript:void(0)')\"></div>",
            touch = { tap() },
            script = "errors.join() + ' ' + document.getElementById('ad').tagName",
        )

        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
        assertEquals("\"open,open DIV\"", shown.scriptResult)
    }

    /**
     * An MRAID creative is loaded with no address of its own (`MraidWebView.loadMraidContent`), so there is nothing to
     * resolve such a link against; the creative's handler carries on after it.
     */
    @Test
    fun aLinkThePageCannotResolveFailsWithAnError() {
        val shown = show(
            "$ERRORS<div id='ad' onclick=\"mraid.open('advertiser landing'); errors.push('after')\"></div>",
            touch = { tap() },
            script = "errors.join()",
        )

        assertEquals(emptyList<String>(), shown.links)
        assertEquals("\"open,after\"", shown.scriptResult)
    }

    @Test
    fun theCreativeIsToldWhatTheAdSupports() {
        val shown = show(
            "",
            script = "['sms', 'tel', 'calendar', 'storePicture', 'inlineVideo', 'vpaid'].map(mraid.supports).join()",
        )

        assertEquals("\"true,true,false,false,true,false\"", shown.scriptResult)
    }

    private companion object {
        /** Collects the action of each MRAID error event in `errors`. */
        const val ERRORS = "<script>window.errors = []; mraid.addEventListener('error', function (message, action) {" +
            "errors.push(action) })</script>"
    }
}
