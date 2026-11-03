package org.bidon.rendering.html

import android.webkit.WebSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.mraid.MraidWebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the configuration of the WebView every creative renders inside.
 *
 * These settings used to be inherited from platform defaults, which changed at API 30, and HTML
 * and MRAID disagreed on one of them. Every assertion here is a decision someone made on purpose;
 * if one starts failing, the question is whether the decision changed, not whether the test is
 * wrong.
 *
 * Instrumented rather than a plain unit test because WebSettings only behaves like itself on a
 * real WebView, and every method here has to run on the thread that built it.
 */
@RunWith(AndroidJUnit4::class)
class HtmlWebViewConfigurationTest {

    private fun htmlWebView() =
        HtmlWebView(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * A WebView may only be touched from the thread that built it, so the assertions run on the
     * main thread and any failure is carried back to the test thread to be reported.
     */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @Test
    fun creativesCanRunScripts() = onMain {
            val settings = htmlWebView().settings
            assertTrue(settings.javaScriptEnabled)
            assertTrue(settings.domStorageEnabled)    }

    @Test
    fun creativeCannotReachTheDeviceFilesystem() = onMain {
            val settings = htmlWebView().settings
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)    }

    @Test
    fun passiveMixedContentIsAllowedButActiveIsNot() = onMain {
            assertEquals(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,
                htmlWebView().settings.mixedContentMode
            )    }

    @Test
    fun mraidInheritsTheSameMixedContentPolicy() = onMain {
            // The regression this exists to catch: MraidWebView used to override this with a
            // different value than its own base class, so behaviour depended on the creative type.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals(
                htmlWebView().settings.mixedContentMode,
                MraidWebView(context).settings.mixedContentMode
            )    }

    @Test
    fun videoCreativesCanStartThemselves() = onMain {
            // Left at the platform default an autoplaying creative renders and never plays, silently.
            assertFalse(htmlWebView().settings.mediaPlaybackRequiresUserGesture)    }

    @Test
    fun theCreativeCannotBeZoomed() = onMain {
            val settings = htmlWebView().settings
            assertFalse(settings.supportZoom())
            assertFalse(settings.builtInZoomControls)
            assertFalse(settings.displayZoomControls)    }

    @Test
    fun textScalingIsPinnedSoASystemFontCannotOverflowTheSlot() = onMain {
            assertEquals(100, htmlWebView().settings.textZoom)    }

    @Test
    fun navigationsStayOnThisWebViewSoClicksAreReported() = onMain {
            val settings = htmlWebView().settings
            assertFalse(settings.supportMultipleWindows())
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)    }

    @Test
    fun theCreativeCannotBeScrolledOutOfPosition() = onMain {
            // Behavioural, not a settings check: turning the scrollbars off only hides the indicator
            // while the view still scrolls, which is what let a creative be dragged out of position.
            val webView = htmlWebView()
            webView.scrollTo(0, 500)
            assertEquals("scrollTo must not move the creative", 0, webView.scrollY)
            assertEquals(0, webView.scrollX)    }
}
