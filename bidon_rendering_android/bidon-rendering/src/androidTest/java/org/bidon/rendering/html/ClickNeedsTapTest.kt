package org.bidon.rendering.html

import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.LinkStarts
import org.bidon.rendering.LocalHttpServer
import org.bidon.rendering.TestCreatives.APP_LINK
import org.bidon.rendering.TestCreatives.FILL
import org.bidon.rendering.TestCreatives.OTHER_APP_LINK
import org.bidon.rendering.TestCreatives.SETTLE_MILLIS
import org.bidon.rendering.tap
import org.bidon.rendering.ui.BlankActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A creative opens a link, and the ad reports a click, only after a tap on the ad, and one tap opens one link. Each
 * creative is shown as an HTML ad is, in a real WebView on a screen of the host app, and is tapped the way a finger
 * taps it.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class ClickNeedsTapTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = LocalHttpServer()

    @After
    fun stopServer() {
        server.close()
    }

    /** [address] is the page the ad shows at the end, as its script sees it. */
    private class Shown(
        val links: List<String>,
        val clicks: Int,
        val pagesLoaded: Int,
        val loadErrors: Int,
        val address: String,
    )

    /** Shows [body] filling the ad, lets it run, taps it if [tapped], and returns what it opened. */
    private fun show(body: String, tapped: Boolean): Shown {
        val clicks = AtomicInteger()
        val pagesLoaded = AtomicInteger()
        val loadErrors = AtomicInteger()
        val loaded = CountDownLatch(1)
        lateinit var webView: HtmlWebView
        var address = ""
        val links = ActivityScenario.launch(BlankActivity::class.java).use { scenario ->
            LinkStarts.during {
                try {
                    scenario.onActivity { activity ->
                        webView = HtmlWebView(activity)
                        webView.webViewClient = ClickHandler(
                            activity,
                            onClick = { clicks.incrementAndGet() },
                            onPageLoaded = {
                                pagesLoaded.incrementAndGet()
                                loaded.countDown()
                            },
                            onPageLoadError = { _, _ -> loadErrors.incrementAndGet() },
                        )
                        activity.setContentView(webView)
                        webView.loadHtmlContent("$FILL$body", baseUrl = server.url("/"))
                    }
                    assertTrue("the creative did not load", loaded.await(10, TimeUnit.SECONDS))
                    SystemClock.sleep(SETTLE_MILLIS)
                    if (tapped) {
                        instrumentation.runOnMainSync { webView.tap() }
                        SystemClock.sleep(SETTLE_MILLIS)
                    }
                    address = addressOf(webView)
                } finally {
                    instrumentation.runOnMainSync {
                        (webView.parent as ViewGroup).removeView(webView)
                        webView.destroy()
                    }
                }
            }
        }
        return Shown(links, clicks.get(), pagesLoaded.get(), loadErrors.get(), address)
    }

    private fun addressOf(webView: HtmlWebView): String {
        val done = CountDownLatch(1)
        var address = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("location.href") {
                address = it
                done.countDown()
            }
        }
        assertTrue("the page did not answer", done.await(5, TimeUnit.SECONDS))
        return address
    }

    @Test
    fun aCreativeThatOpensALinkAsItLoadsOpensNothing() {
        val shown = show("<script>location.href = '$APP_LINK'</script>", tapped = false)

        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    @Test
    fun anIframeThatRedirectsToAnAppAsItLoadsOpensNothing() {
        server.redirect("/frame", APP_LINK)

        val shown = show("<iframe src='${server.url("/frame")}'></iframe>", tapped = false)

        assertEquals("the iframe was never loaded", 1, server.requestCount("/frame"))
        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    /**
     * A form sent with POST never reaches the code that refuses navigations, so it is turned away on its way out. A
     * tapped one is turned away too: it is neither sent nor counted.
     */
    @Test
    fun aCreativeThatPostsAFormAsItLoadsStaysTheAd() {
        val shown = show(
            "<form id='f' method='post' action='${server.url("/form")}'><input name='a' value='b'></form>" +
                "<script>document.getElementById('f').submit()</script>",
            tapped = false,
        )

        assertEquals("the form was sent", 0, server.requestCount("/form"))
        assertEquals("the ad was replaced by another page", "\"${server.url("/")}\"", shown.address)
        assertEquals("the ad was loaded again", 1, shown.pagesLoaded)
        assertEquals("the ad was reported as failed to load", 0, shown.loadErrors)
        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    @Test
    fun aTappedFormIsNotSentEither() {
        val shown = show(
            "<form style='height: 100%' method='post' action='${server.url("/form")}'>" +
                "<button id='ad'>Send</button></form>",
            tapped = true,
        )

        assertEquals("the form was sent", 0, server.requestCount("/form"))
        assertEquals("the ad was replaced by another page", "\"${server.url("/")}\"", shown.address)
        assertEquals(emptyList<String>(), shown.links)
        assertEquals(0, shown.clicks)
    }

    @Test
    fun aTapOnALinkOpensItAndCountsOnce() {
        val shown = show("<a id='ad' href='$APP_LINK'>ad</a>", tapped = true)

        assertEquals(listOf(APP_LINK), shown.links)
        assertEquals(1, shown.clicks)
    }

    /**
     * The WebView hands a tap to the first navigation that follows it and to no other; the ad keeps no count of its
     * own, so this shows the WebView's rule, measured on WebView 124.
     */
    @Test
    fun aTapOpensOneLinkHoweverManyTheCreativeTries() {
        val shown = show(
            "<div id='ad' onclick=\"location.href = '$APP_LINK'; " +
                "setTimeout(function () { location.href = '$OTHER_APP_LINK' }, 300)\"></div>",
            tapped = true,
        )

        assertEquals(listOf(APP_LINK), shown.links)
        assertEquals(1, shown.clicks)
    }

    /** The ad opens the tracker's address and the browser follows its redirects; the ad itself never loads it. */
    @Test
    fun aTapOnATrackedLinkOpensTheTrackerInTheBrowser() {
        server.redirect("/click", APP_LINK)
        val tracker = server.url("/click")

        val shown = show("<a id='ad' href='$tracker'>ad</a>", tapped = true)

        assertEquals(listOf(tracker), shown.links)
        assertEquals(1, shown.clicks)
        assertEquals("the ad followed the tracker itself", 0, server.requestCount("/click"))
    }
}
