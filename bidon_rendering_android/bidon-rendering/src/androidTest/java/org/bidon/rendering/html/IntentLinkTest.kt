package org.bidon.rendering.html

import android.app.Activity
import android.app.Instrumentation
import android.app.Instrumentation.ActivityMonitor
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A link in a creative opens only what a browser would open: an app that accepts links from the web, never a screen
 * of the host app that is not exported.
 *
 * Links are started from the host app's own context, and the app may start its own screens whether they are exported
 * or not, so a link could otherwise reach any of them with extras the creative chose. Each `intent:` test link carries
 * a fallback page: it opens only when nothing on the device may take the link, so a fallback that opens shows that the
 * host's screen was out of reach. The monitor intercepts the fallback before a browser opens.
 */
@RunWith(AndroidJUnit4::class)
class IntentLinkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val host = context.packageName

    private val webPage = ActivityMonitor(
        IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https")
            // A filter matches only intents whose categories it has; the links opened are browsable.
            addCategory(Intent.CATEGORY_BROWSABLE)
        },
        null,
        true,
    )

    init {
        instrumentation.addMonitor(webPage)
    }

    @After
    fun cleanUp() {
        instrumentation.removeMonitor(webPage)
        instrumentation.runOnMainSync { startedScreens().forEach(Activity::finish) }
    }

    private class Request(private val uri: Uri) : WebResourceRequest {
        override fun getUrl(): Uri = uri
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private fun click(url: String) {
        instrumentation.runOnMainSync {
            ClickHandler(context, onClick = {}).shouldOverrideUrlLoading(null, Request(Uri.parse(url)))
        }
        instrumentation.waitForIdleSync()
    }

    private fun withFallback(link: String) = "$link;S.browser_fallback_url=${Uri.encode(FALLBACK)};end"

    private fun startedScreens(): List<Activity> = Stage.values().filter { it != Stage.DESTROYED }
        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }

    /** The named screen is exported, so only dropping the component keeps the link from choosing it. */
    @Test
    fun aLinkNamingAScreenOpensItsFallbackInstead() {
        click(withFallback("intent://x#Intent;scheme=bidontest-none;component=$host/${HostViewActivity::class.java.name}"))

        assertEquals(1, webPage.hits)
    }

    /** Everything after `SEL` belongs to the selector, so the fallback comes before it. */
    @Test
    fun aLinkThatChoosesAScreenThroughASelectorOpensItsFallbackInstead() {
        click(
            "intent://x#Intent;scheme=bidontest-none;action=android.intent.action.VIEW;" +
                "S.browser_fallback_url=${Uri.encode(FALLBACK)};" +
                "SEL;component=$host/${HostViewActivity::class.java.name};end",
        )

        assertEquals(1, webPage.hits)
    }

    /** The screen views such links and may be started by anyone, but never declared itself openable from the web. */
    @Test
    fun aLinkOnlyANonBrowsableScreenTakesOpensItsFallbackInstead() {
        click(withFallback("intent://x#Intent;scheme=bidontest-internal;action=android.intent.action.VIEW"))

        assertEquals(1, webPage.hits)
    }

    /** The host's screen opens from its own web links, but is not exported: a creative is not the app. */
    @Test
    fun aLinkToTheHostsOwnDeepLinkScreenOpensItsFallbackInstead() {
        click(withFallback("intent://screen#Intent;scheme=bidontest;package=$host;action=android.intent.action.VIEW"))

        assertEquals(1, webPage.hits)
    }

    /**
     * A plain link has no fallback, so this one watches for the start itself: the link is refused before it is
     * started, and the monitor sees every start synchronously.
     */
    @Test
    fun aPlainLinkToTheHostsOwnDeepLinkScreenOpensNothing() {
        val ownLinks = ActivityMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("bidontest")
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            null,
            true,
        )
        instrumentation.addMonitor(ownLinks)
        try {
            click("bidontest://screen")

            assertEquals(0, ownLinks.hits)
        } finally {
            instrumentation.removeMonitor(ownLinks)
        }
    }

    /**
     * A link with no address could still be taken by a screen that opens from addressless web links, and the system
     * would show its app chooser for it; it opens its fallback instead.
     */
    @Test
    fun aLinkWithoutAnAddressOpensItsFallbackPage() {
        click(withFallback("intent:#Intent;action=android.intent.action.VIEW"))

        assertEquals(1, webPage.hits)
    }

    /**
     * A web link stays a web link even where the host routes that site to a screen of its own: the system chooses, as
     * for any link. Refusing it would leave every click-through to that site dead.
     */
    @Test
    fun aWebLinkToASiteTheHostRoutesItselfStillOpens() {
        click("https://host-routed.example/landing")

        assertEquals(1, webPage.hits)
    }

    /** An `intent:` link carries extras the creative chose, so it may not reach the host's screen even for a website. */
    @Test
    fun anIntentLinkToASiteTheHostRoutesItselfOpensNothing() {
        click("intent://host-routed.example/landing#Intent;scheme=https;action=android.intent.action.VIEW;end")

        assertEquals(0, webPage.hits)
    }

    /**
     * Watches for starts of [HostLinkActivity] and keeps each one's intent, without letting the screen open. A monitor
     * that sees every start, whatever it matches, came with Android 8.
     */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    private class LinkScreenStarts : ActivityMonitor() {
        val intents = mutableListOf<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.data?.scheme != "bidontest-open") return null
            intents += intent
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    private fun linkScreenStartsDuring(clicks: () -> Unit): List<Intent> {
        val starts = LinkScreenStarts()
        instrumentation.addMonitor(starts)
        try {
            clicks()
        } finally {
            instrumentation.removeMonitor(starts)
        }
        return starts.intents
    }

    /** The screen is the host's, but it is exported and accepts links like any app that takes them from the web. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun anIntentLinkToAScreenThatAcceptsLinksOpensIt() {
        val starts = linkScreenStartsDuring {
            click("intent://x#Intent;scheme=bidontest-open;action=android.intent.action.VIEW;end")
        }

        assertEquals(1, starts.size)
    }

    /** `0x8000` is `FLAG_ACTIVITY_CLEAR_TASK`: started as a new task, the screen would end the host's own screens. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun aLinkCannotChooseHowItsScreenStarts() {
        val starts = linkScreenStartsDuring {
            click("intent://x#Intent;scheme=bidontest-open;action=android.intent.action.VIEW;launchFlags=0x8000;end")
        }

        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, starts.single().flags)
    }

    /** As in a browser, the fallback is a web page; one that names an app's link is not followed. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun aFallbackThatIsNotAWebPageOpensNothing() {
        val starts = linkScreenStartsDuring {
            click("intent://x#Intent;scheme=bidontest-none;S.browser_fallback_url=${Uri.encode("bidontest-open://x")};end")
        }

        assertEquals(0, starts.size)
    }

    /** A scheme is matched as written, so an uppercase one is brought to the form apps declare. */
    @Test
    fun aFallbackPageWithAnUppercaseSchemeStillOpens() {
        click("intent://x#Intent;scheme=bidontest-none;S.browser_fallback_url=${Uri.encode("HTTPS://advertiser.example/f")};end")

        assertEquals(1, webPage.hits)
    }

    @Test
    fun aWebLinkStillOpens() {
        click("intent://advertiser.example/landing#Intent;scheme=https;action=android.intent.action.VIEW;end")
        click("https://advertiser.example/landing")

        assertEquals(2, webPage.hits)
    }

    private companion object {
        const val FALLBACK = "https://advertiser.example/fallback"
    }
}
