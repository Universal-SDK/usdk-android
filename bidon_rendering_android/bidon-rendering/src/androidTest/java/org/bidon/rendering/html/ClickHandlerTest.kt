package org.bidon.rendering.html

import android.app.Instrumentation.ActivityMonitor
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The invariant: the ad's WebView renders the creative and never navigates anywhere else.
 *
 * Returning false from shouldOverrideUrlLoading hands the URL back to the WebView, which replaces
 * the creative with the destination page inside the ad container - the ad becomes a website with
 * a close button over it. Only the creative talking to itself may be passed through.
 */
@RunWith(AndroidJUnit4::class)
class ClickHandlerTest {

    private class FakeRequest(private val uri: Uri, private val gesture: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = uri
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private var clicks = 0

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * Keeps a web or store link the handler opens from reaching the browser or the store. One left on top of the test
     * app's screens holds up every later test that waits for one of them to show.
     */
    private val webPage = ActivityMonitor(
        IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https")
            addDataScheme("market")
            addCategory(Intent.CATEGORY_BROWSABLE)
        },
        null,
        true,
    ).also { instrumentation.addMonitor(it) }

    @After
    fun removeMonitor() {
        instrumentation.removeMonitor(webPage)
    }

    private fun navigateTo(url: String, tapped: Boolean = true): Boolean {
        val handler = ClickHandler(
            context = instrumentation.targetContext,
            onClick = { clicks++ }
        )
        return handler.shouldOverrideUrlLoading(null, FakeRequest(Uri.parse(url), tapped))
    }

    @Test
    fun anOrdinaryClickThroughIsTakenOverNotLoaded() {
        assertTrue(navigateTo("https://example.com/landing"))
        assertEquals("not opened outside the ad", 1, webPage.hits)
    }

    @Test
    fun aClickThatCannotBeOpenedIsStillRefused() {
        // Nothing on the device claims these. Before, the failure returned false and the WebView
        // loaded the destination itself.
        assertTrue(navigateTo("fake-scheme-nothing-handles://open"))
        assertTrue(navigateTo("market://details?id=com.example.absent"))
    }

    @Test
    fun aUrlWithNoSchemeIsStillRefused() {
        assertTrue(navigateTo("not-a-real-url"))
    }

    @Test
    fun aMalformedIntentUrlIsStillRefused() {
        assertTrue(navigateTo("intent://#Intent;broken;end"))
    }

    /** With a tap or without: a creative runs its own script whenever it likes. */
    @Test
    fun theCreativeTalkingToItselfIsLetThrough() {
        assertFalse(navigateTo("javascript:void(0)"))
        assertFalse(navigateTo("about:blank"))
        assertFalse(navigateTo("javascript:void(0)", tapped = false))
        assertFalse(navigateTo("about:blank", tapped = false))
    }

    @Test
    fun aClickIsReportedEvenWhenTheDestinationWillNotOpen() {
        // The user did tap the ad. Only the destination failed to open.
        clicks = 0
        navigateTo("fake-scheme-nothing-handles://open")
        assertEquals(1, clicks)
    }

    /** It is still refused: the ad never becomes the page it was sent to. */
    @Test
    fun aNavigationWithoutATapIsRefusedAndIsNotAClick() {
        clicks = 0
        assertTrue(navigateTo("https://example.com/landing", tapped = false))
        assertEquals(0, clicks)
        assertEquals("opened outside the ad", 0, webPage.hits)
    }

    @Test
    fun theCreativeTalkingToItselfIsNotAClick() {
        clicks = 0
        navigateTo("javascript:void(0)")
        assertEquals(0, clicks)
    }
}
