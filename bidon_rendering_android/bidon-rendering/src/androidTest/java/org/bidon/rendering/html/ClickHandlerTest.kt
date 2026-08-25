package org.bidon.rendering.html

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private class FakeRequest(private val uri: Uri) : WebResourceRequest {
        override fun getUrl(): Uri = uri
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private var clicks = 0

    private fun navigateTo(url: String): Boolean {
        val handler = ClickHandler(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            onClick = { clicks++ }
        )
        return handler.shouldOverrideUrlLoading(null, FakeRequest(Uri.parse(url)))
    }

    @Test
    fun anOrdinaryClickThroughIsTakenOverNotLoaded() {
        assertTrue(navigateTo("https://example.com/landing"))
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

    @Test
    fun theCreativeTalkingToItselfIsLetThrough() {
        assertFalse(navigateTo("javascript:void(0)"))
        assertFalse(navigateTo("about:blank"))
    }

    @Test
    fun aClickIsReportedEvenWhenTheDestinationWillNotOpen() {
        // The user did tap the ad. Only the destination failed to open.
        clicks = 0
        navigateTo("fake-scheme-nothing-handles://open")
        assertEquals(1, clicks)
    }

    @Test
    fun theCreativeTalkingToItselfIsNotAClick() {
        clicks = 0
        navigateTo("javascript:void(0)")
        assertEquals(0, clicks)
    }
}
