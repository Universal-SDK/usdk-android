package org.bidon.bidon.impl

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class BidonAdapterShowErrorTest(private val kind: Kind) {

    enum class Kind { INTERSTITIAL, REWARDED, BANNER }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<Kind> = Kind.values().toList()

        private const val TIMEOUT_MS = 5_000L
    }

    private class FakeRenderer : AdRenderer {
        lateinit var listener: AdRenderer.Listener
        var onShowFullscreen: () -> Unit = {}
        var adView: View? = null

        override fun load(adm: String, listener: AdRenderer.Listener) {
            this.listener = listener
        }

        override fun attach(container: ViewGroup) = Unit
        override fun showFullscreen(activity: Activity) = onShowFullscreen()
        override fun destroy() = Unit
        override fun getView(): View? = adView
    }

    private class Subject(
        val renderer: FakeRenderer,
        val flow: Flow<AdEvent>,
        val load: () -> Unit,
        val show: () -> Unit,
        val destroy: () -> Unit,
        val stats: StatisticsCollector,
    ) {
        val events = mutableListOf<AdEvent>()
    }

    private val activity = mockk<Activity>(relaxed = true)
    private val adUnit = mockk<AdUnit>(relaxed = true) {
        every { timeout } returns TIMEOUT_MS
        every { pricefloor } returns 0.0
    }
    private val created = mutableListOf<Subject>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(BidonRenderer)
    }

    @After
    fun tearDown() {
        created.forEach { it.destroy() }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun fullscreenParams(): BidonFullscreenAuctionParams {
        val rendering = AdRenderingConfig.fromJson(null)
        return BidonFullscreenAuctionParams(
            activity = activity,
            adm = "<html></html>",
            creativeType = CreativeType.of("<html></html>", rendering),
            rendering = rendering,
            price = 0.0,
            adUnit = adUnit,
        )
    }

    private fun bannerParams(): BidonBannerAuctionParams {
        val rendering = AdRenderingConfig.fromJson(null)
        return BidonBannerAuctionParams(
            activity = activity,
            adm = "<html></html>",
            creativeType = CreativeType.of("<html></html>", rendering),
            rendering = rendering,
            price = 0.0,
            adUnit = adUnit,
        )
    }

    private fun createSubject(): Subject {
        val renderer = FakeRenderer()
        every { BidonRenderer.createRenderer(any(), any(), any()) } returns renderer
        val subject = when (kind) {
            Kind.INTERSTITIAL -> BidonInterstitialImpl().let { a ->
                Subject(renderer, a.adEvent, { a.load(fullscreenParams()) }, { a.show(activity) }, a::destroy, a)
            }
            Kind.REWARDED -> BidonRewardedImpl().let { a ->
                Subject(renderer, a.adEvent, { a.load(fullscreenParams()) }, { a.show(activity) }, a::destroy, a)
            }
            Kind.BANNER -> BidonBannerImpl().let { a ->
                // The banner is shown by placing the renderer's view, so the host gets that view and no other.
                renderer.adView = mockk<View>()
                val show = { assertSame(renderer.adView, a.getAdView()?.networkAdview) }
                Subject(renderer, a.adEvent, { a.load(bannerParams()) }, show, a::destroy, a)
            }
        }
        created += subject
        return subject
    }

    /** A subject that has loaded and heard the renderer load; [beforeLoad] runs on it first. */
    private fun TestScope.loadedSubject(beforeLoad: (Subject) -> Unit = {}): Subject {
        val subject = createSubject()
        beforeLoad(subject)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subject.flow.collect { subject.events += it }
        }
        subject.load()
        subject.renderer.listener.onLoaded()
        return subject
    }

    private val error = RuntimeException("boom")

    @Test
    fun `error before show is LoadFailed`() = runTest {
        val s = loadedSubject()

        s.renderer.listener.onError(error)

        assertTrue(s.events.last() is AdEvent.LoadFailed)
        assertTrue(s.events.none { it is AdEvent.ShowFailed })
    }

    @Test
    fun `error after show and before the impression is ShowFailed`() = runTest {
        val s = loadedSubject()
        s.show()

        s.renderer.listener.onError(error)

        val failed = s.events.last() as AdEvent.ShowFailed
        assertTrue(failed.cause is BidonError.Unspecified)
        assertTrue(s.events.none { it is AdEvent.LoadFailed })
    }

    @Test
    fun `error reported synchronously from showFullscreen is ShowFailed`() = runTest {
        assumeTrue(kind != Kind.BANNER)
        val s = loadedSubject()
        s.renderer.onShowFullscreen = { s.renderer.listener.onError(error) }

        s.show()

        assertTrue(s.events.last() is AdEvent.ShowFailed)
        assertTrue(s.events.none { it is AdEvent.LoadFailed })
    }

    @Test
    fun `error after Shown was emitted reports nothing`() = runTest {
        val s = loadedSubject()
        s.show()
        s.renderer.listener.onImpression()
        val before = s.events.size

        s.renderer.listener.onError(error)

        assertEquals(before, s.events.size)
    }

    @Test
    fun `a second impression from the renderer reports Shown once`() = runTest {
        // Shown carries the mediation SDK's ad, which exists only once the auction round and the fill are recorded.
        every { adUnit.bidType } returns BidType.RTB
        val s = loadedSubject { subject ->
            subject.stats.addRoundInfo("auction-1", DemandAd(AdType.Interstitial), auctionPricefloor = 0.0)
            subject.stats.markFillStarted(adUnit, pricefloor = 0.0)
        }
        s.show()

        s.renderer.listener.onImpression()
        s.renderer.listener.onImpression()

        assertEquals(1, s.events.count { it is AdEvent.Shown })
    }

    @Test
    fun `error after a new load following a show is LoadFailed`() = runTest {
        val s = loadedSubject()
        s.show()
        s.load()

        s.renderer.listener.onError(error)

        assertTrue(s.events.last() is AdEvent.LoadFailed)
        assertTrue(s.events.none { it is AdEvent.ShowFailed })
    }

    @Test
    fun `error after a new load following an impression is LoadFailed`() = runTest {
        val s = loadedSubject()
        s.show()
        s.renderer.listener.onImpression()
        s.load()

        s.renderer.listener.onError(error)

        assertTrue(s.events.last() is AdEvent.LoadFailed)
    }
}
