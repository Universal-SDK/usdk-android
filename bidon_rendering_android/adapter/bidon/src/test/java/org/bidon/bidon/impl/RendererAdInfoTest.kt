package org.bidon.bidon.impl

import org.bidon.rendering.AdType
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RendererAdInfoTest {

    @Test
    fun theAdIsLabelledWithTheAuctionTheMediationSdkRan() {
        val collector = StatisticsCollectorImpl().apply {
            addRoundInfo(
                auctionId = "auction-1",
                demandAd = DemandAd(org.bidon.sdk.ads.AdType.Rewarded),
                auctionPricefloor = 0.1,
            )
        }

        val info = collector.adInfo(AdType.REWARDED)

        assertEquals("auction-1", info.auctionId)
        assertEquals(AdType.REWARDED, info.type)
    }

    /** The mediation SDK's getter throws before the auction is set; a label must not fail the load. */
    @Test
    fun anAdWithNoAuctionYetIsLabelledWithoutOne() {
        val info = StatisticsCollectorImpl().adInfo(AdType.BANNER)

        assertNull(info.auctionId)
        assertEquals(AdType.BANNER, info.type)
    }
}
