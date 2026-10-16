package org.bidon.rendering.mraid

import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.bidon.rendering.CloseButtonConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
class MraidReadyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var webView: MraidWebView? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webView?.destroy() }
    }

    /** What the caller does after `onReady` can rely on the creative having heard `ready`. */
    @Test
    fun theCallbackRunsOnlyAfterTheCreativeHasHeardReady() {
        val loaded = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var heard = ""
        val mraidJs = context.assets.open("mraid.js").bufferedReader().use { it.readText() }
        val html = "<html><body><p>ad</p><script>" +
            "window.heard = false;" +
            "mraid.addEventListener('ready', function () { window.heard = true })" +
            "</script></body></html>"

        instrumentation.runOnMainSync {
            val view = MraidWebView(context).also { webView = it }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
            }
            view.loadMraidContent(html, mraidJs)
        }
        assertTrue("the page did not load", loaded.await(10, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            val view = webView!!
            val controller = MraidController(context, view, MraidPlacementType.INLINE, CloseButtonConfig()) {}
            controller.onReady(afterReady = {
                view.evaluateJavascript("String(window.heard === true)") {
                    heard = it.trim('"')
                    answered.countDown()
                }
            })
        }

        assertTrue("the callback never ran", answered.await(10, TimeUnit.SECONDS))
        assertEquals("true", heard)
    }

    /** An ad that is gone is not told anything, and nothing runs for it. */
    @Test
    fun theCallbackNeverRunsOnceTheControllerIsDestroyed() {
        val loaded = CountDownLatch(1)
        val mraidJs = context.assets.open("mraid.js").bufferedReader().use { it.readText() }
        var ran = false

        instrumentation.runOnMainSync {
            val view = MraidWebView(context).also { webView = it }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
            }
            view.loadMraidContent("<html><body><p>ad</p></body></html>", mraidJs)
        }
        assertTrue("the page did not load", loaded.await(10, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            val controller = MraidController(context, webView!!, MraidPlacementType.INLINE, CloseButtonConfig()) {}
            controller.destroy()
            controller.onReady(afterReady = { ran = true })
        }
        // Let the main thread run whatever was posted.
        instrumentation.waitForIdleSync()
        Thread.sleep(500)

        assertFalse("the callback ran after destroy", ran)
    }
}
