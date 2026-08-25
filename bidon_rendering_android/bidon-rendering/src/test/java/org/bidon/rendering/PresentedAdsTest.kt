package org.bidon.rendering

import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PresentedAdsTest {

    private fun stub() = object : PresentedAd {
        override val contentView: View? get() = null
        override val closeDelayMs: Long get() = 0L
        override val lockedOrientation: Int get() = 0
        override var closeRequester: (() -> Unit)? = null
        override fun onImpression() = Unit
        override fun onClicked() = Unit
        override fun onClosed() = Unit
        override fun onRewarded() = Unit
        override fun onFailed(error: Throwable) = Unit
    }

    @Test
    fun `a registered ad is found by its key`() {
        val ad = stub()
        val key = PresentedAds.register(ad)
        assertSame(ad, PresentedAds.get(key))
        PresentedAds.unregister(key)
    }

    @Test
    fun `looking up does not consume`() {
        // The activity is recreated on a configuration change and looks the same key up again.
        // A take-on-read registry would leave it with no ad the second time.
        val key = PresentedAds.register(stub())
        assertNotNull(PresentedAds.get(key))
        assertNotNull(PresentedAds.get(key))
        PresentedAds.unregister(key)
    }

    @Test
    fun `unregister removes it`() {
        val key = PresentedAds.register(stub())
        PresentedAds.unregister(key)
        assertNull(PresentedAds.get(key))
    }

    @Test
    fun `each registration gets its own key`() {
        val first = PresentedAds.register(stub())
        val second = PresentedAds.register(stub())
        assert(first != second)
        assertSame(PresentedAds.get(first), PresentedAds.get(first))
        assert(PresentedAds.get(first) !== PresentedAds.get(second))
        PresentedAds.unregister(first)
        PresentedAds.unregister(second)
    }

    @Test
    fun `an unknown or null key is simply absent`() {
        assertNull(PresentedAds.get(null))
        assertNull(PresentedAds.get("never-registered"))
        PresentedAds.unregister(null) // must not throw
    }
}
