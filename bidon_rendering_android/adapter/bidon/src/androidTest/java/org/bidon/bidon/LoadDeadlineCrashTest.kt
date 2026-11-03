package org.bidon.bidon

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.bidon.bidon.impl.BidonBannerImpl
import org.bidon.bidon.impl.BidonInterstitialImpl
import org.bidon.bidon.impl.BidonRewardedImpl
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * An exception thrown from a load timeout, on the scope each ad class really runs it on, is delivered to the thread's
 * default handler and not swallowed. That handler is where the renderer's crash capture sits, so a crash here is
 * recorded and still reaches the host's reporter.
 *
 * The adapter cannot see crash capture, so the test installs a plain handler and checks delivery to it; that an
 * adapter frame makes the crash ours is checked in the renderer module.
 *
 * The throwing timeouts are built here, on the scope read from the ad, because the real one only logs and reports a
 * failure. So the tests that load an ad check the other half: the timeout its own `load` starts runs on that same
 * scope.
 */
@RunWith(AndroidJUnit4::class)
class LoadDeadlineCrashTest {

    private class Delivered(val thread: Thread, val throwable: Throwable)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val delivered = AtomicReference<Delivered>()
    private val arrived = CountDownLatch(1)

    private val adUnit = AdUnit(
        demandId = "bidon",
        label = "test",
        pricefloor = 1.0,
        uid = "test",
        bidType = BidType.RTB,
        // Far beyond the test, so the timer is still waiting when it is checked.
        timeout = 60_000,
        ext = null,
    )

    @Before
    fun installPlainDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            delivered.compareAndSet(null, Delivered(thread, throwable))
            arrived.countDown()
        }
    }

    @After
    fun restoreDefaultHandler() = Thread.setDefaultUncaughtExceptionHandler(original)

    @Test
    fun aTimeoutThatThrowsOnAnInterstitialsScopeReachesTheDefaultHandler() = assertDelivered(scopeOf(BidonInterstitialImpl()))

    @Test
    fun aTimeoutThatThrowsOnARewardedAdsScopeReachesTheDefaultHandler() = assertDelivered(scopeOf(BidonRewardedImpl()))

    @Test
    fun aTimeoutThatThrowsOnABannersScopeReachesTheDefaultHandler() = assertDelivered(scopeOf(BidonBannerImpl()))

    @Test
    fun anInterstitialStartsItsLoadTimeoutOnItsOwnScope() {
        val ad = BidonInterstitialImpl()
        assertTimeoutOnOwnScope(ad, load = { ad.load(fullscreenParams()) }, destroy = ad::destroy)
    }

    @Test
    fun aRewardedAdStartsItsLoadTimeoutOnItsOwnScope() {
        val ad = BidonRewardedImpl()
        assertTimeoutOnOwnScope(ad, load = { ad.load(fullscreenParams()) }, destroy = ad::destroy)
    }

    @Test
    fun aBannerStartsItsLoadTimeoutOnItsOwnScope() {
        val ad = BidonBannerImpl()
        assertTimeoutOnOwnScope(ad, load = { ad.load(bannerParams()) }, destroy = ad::destroy)
    }

    /** The `MainScope()` the ad class launches its load timer on, read from the instance so it is the real one. */
    private fun scopeOf(ad: Any): CoroutineScope = field(ad, "scope") as CoroutineScope

    private fun field(owner: Any, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!

    /**
     * Loads [ad] through its own `load` and checks that the timer of the deadline it started is a child of the scope
     * [scopeOf] reads, before the page can finish and settle the deadline.
     */
    private fun assertTimeoutOnOwnScope(ad: Any, load: () -> Unit, destroy: () -> Unit) {
        BidonRenderer.init(instrumentation.targetContext)
        var onOwnScope = false
        instrumentation.runOnMainSync {
            load()
            val timer = field(field(ad, "loadDeadline"), "timer") as Job
            onOwnScope = timer in scopeOf(ad).coroutineContext.job.children
            destroy()
        }
        assertTrue("the load timeout runs on a scope other than the ad's own", onOwnScope)
    }

    // Loading never reads the activity, only showing does; one is built on the main thread, which it needs.
    private fun fullscreenParams() =
        BidonFullscreenAuctionParams(Activity(), ADM, CreativeType.HTML, AdRenderingConfig.fromJson(null), 1.0, adUnit)

    private fun bannerParams() =
        BidonBannerAuctionParams(Activity(), ADM, CreativeType.HTML, AdRenderingConfig.fromJson(null), 1.0, adUnit)

    private fun assertDelivered(scope: CoroutineScope) {
        val thrown = IllegalStateException("ours")

        RendererLoadDeadline(scope, timeoutMs = 50, listener = NoListener, onTimeout = { throw thrown })

        assertTrue("the exception never reached the default handler", arrived.await(10, TimeUnit.SECONDS))
        val reached = delivered.get()
        assertSame(thrown, reached.throwable)
        assertEquals("main", reached.thread.name)
    }

    private companion object {
        const val ADM = "<html><body><p>ad</p></body></html>"
    }

    private object NoListener : AdRenderer.Listener {
        override fun onLoaded() = Unit
        override fun onImpression() = Unit
        override fun onClick() = Unit
        override fun onClose() = Unit
        override fun onReward() = Unit
        override fun onError(error: Throwable) = Unit
    }
}
