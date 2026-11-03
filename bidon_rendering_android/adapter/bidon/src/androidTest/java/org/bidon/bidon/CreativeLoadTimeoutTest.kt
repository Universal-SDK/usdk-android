package org.bidon.bidon

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.bidon.bidon.impl.BidonBannerImpl
import org.bidon.bidon.impl.BidonInterstitialImpl
import org.bidon.bidon.impl.BidonRewardedImpl
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.stats.models.BidType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A creative whose page never finishes loading, because an image on it never arrives, fails once at the ad unit's
 * timeout, and its WebView is destroyed: the request it was waiting on is dropped.
 */
@RunWith(AndroidJUnit4::class)
class CreativeLoadTimeoutTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = StalledServer()
    private val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Collections.synchronizedList(mutableListOf<AdEvent>())
    private val firstEvent = CountDownLatch(1)

    private val adm = "<html><body><img src=\"${server.url}\"></body></html>"
    private val adUnit = AdUnit(
        demandId = "bidon",
        label = "test",
        pricefloor = 1.0,
        uid = "test",
        bidType = BidType.RTB,
        timeout = TIMEOUT_MS,
        ext = null,
    )

    init {
        BidonRenderer.init(instrumentation.targetContext)
    }

    @After
    fun cleanUp() {
        collector.cancel()
        server.close()
    }

    // Loading never reads the activity, only showing does; one is built on the main thread, which it needs.
    private fun activity(): Activity = Activity()

    private fun record(adEvents: Flow<AdEvent>) {
        collector.launch(start = CoroutineStart.UNDISPATCHED) {
            adEvents.collect {
                events += it
                firstEvent.countDown()
            }
        }
    }

    private fun assertOneTimeoutAndTheLoadDropped() {
        assertTrue("the creative never requested its image", server.requested.await(5, TimeUnit.SECONDS))
        assertTrue("no event at the timeout", firstEvent.await(5, TimeUnit.SECONDS))
        // Destroying the WebView drops its request at once; a WebView only let go of would drop it much later.
        assertTrue("the WebView kept its request open", server.dropped.await(2, TimeUnit.SECONDS))
        // Long enough for a second report, had there been one.
        Thread.sleep(TIMEOUT_MS)

        val reported = synchronized(events) { events.toList() }
        assertEquals(1, reported.size)
        val failure = reported.single() as AdEvent.LoadFailed
        assertTrue(failure.cause is BidonError.FillTimedOut)
    }

    private fun interstitial() = BidonInterstitialImpl().also { ad ->
        record(ad.adEvent)
        instrumentation.runOnMainSync {
            ad.load(BidonFullscreenAuctionParams(activity(), adm, CreativeType.HTML, AdRenderingConfig.fromJson(null), 1.0, adUnit))
        }
    }

    private fun rewarded() = BidonRewardedImpl().also { ad ->
        record(ad.adEvent)
        instrumentation.runOnMainSync {
            ad.load(BidonFullscreenAuctionParams(activity(), adm, CreativeType.HTML, AdRenderingConfig.fromJson(null), 1.0, adUnit))
        }
    }

    private fun banner() = BidonBannerImpl().also { ad ->
        record(ad.adEvent)
        instrumentation.runOnMainSync {
            ad.load(BidonBannerAuctionParams(activity(), adm, CreativeType.HTML, AdRenderingConfig.fromJson(null), 1.0, adUnit))
        }
    }

    @Test
    fun anInterstitialThatNeverLoadsFailsOnceAndIsDestroyed() {
        interstitial()

        assertOneTimeoutAndTheLoadDropped()
    }

    @Test
    fun aRewardedAdThatNeverLoadsFailsOnceAndIsDestroyed() {
        rewarded()

        assertOneTimeoutAndTheLoadDropped()
    }

    @Test
    fun aBannerThatNeverLoadsFailsOnceAndIsDestroyed() {
        val ad = banner()

        assertOneTimeoutAndTheLoadDropped()
        instrumentation.runOnMainSync { assertNull("a timed-out banner still has a view", ad.getAdView()) }
    }

    /** An ad the mediation SDK destroys while it loads, as when the auction is cancelled, reports nothing more. */
    @Test
    fun anAdDestroyedWhileItLoadsReportsNoTimeout() {
        val ads = listOf(interstitial(), rewarded(), banner())
        assertTrue("the creative never requested its image", server.requested.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync { ads.forEach { it.destroy() } }
        Thread.sleep(2 * TIMEOUT_MS)

        assertEquals(emptyList<AdEvent>(), synchronized(events) { events.toList() })
    }

    /** Accepts one request and never answers it, and tells when the client hangs up. */
    private class StalledServer : AutoCloseable {

        private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

        val url = "http://127.0.0.1:${socket.localPort}/image.png"
        val requested = CountDownLatch(1)
        val dropped = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                runCatching {
                    socket.accept().use { connection ->
                        val input = connection.getInputStream()
                        requested.countDown()
                        // Nothing is ever sent back, so the only way a read ends is the client closing the connection.
                        while (input.read() >= 0) Unit
                        dropped.countDown()
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
    }
}
