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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bidon.bidon.BidonBannerAuctionParams
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.CreativeType
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.junit.After
import org.junit.Assert.assertEquals
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

        override fun load(adm: String, listener: AdRenderer.Listener) {
            this.listener = listener
        }

        override fun attach(container: ViewGroup) = Unit
        override fun showFullscreen(activity: Activity) = onShowFullscreen()
        override fun destroy() = Unit
        override fun getView(): View? = null
    }

    private class Subject(
        val renderer: FakeRenderer,
        val flow: Flow<AdEvent>,
        val load: () -> Unit,
        val show: () -> Unit,
        val destroy: () -> Unit,
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
                Subject(renderer, a.adEvent, { a.load(fullscreenParams()) }, { a.show(activity) }, a::destroy)
            }
            Kind.REWARDED -> BidonRewardedImpl().let { a ->
                Subject(renderer, a.adEvent, { a.load(fullscreenParams()) }, { a.show(activity) }, a::destroy)
            }
            Kind.BANNER -> BidonBannerImpl().let { a ->
                Subject(renderer, a.adEvent, { a.load(bannerParams()) }, { a.getAdView() }, a::destroy)
            }
        }
        created += subject
        return subject
    }

    private fun TestScope.loadedSubject(): Subject {
        val subject = createSubject()
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
    fun `no LoadFailed is emitted after show when the load deadline passes`() = runTest {
        val s = loadedSubject()
        s.show()

        advanceTimeBy(TIMEOUT_MS + 1)
        runCurrent()

        assertTrue(s.events.none { it is AdEvent.LoadFailed })
    }
}