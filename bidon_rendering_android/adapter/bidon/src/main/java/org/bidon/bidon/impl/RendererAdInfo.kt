package org.bidon.bidon.impl

import org.bidon.rendering.AdType
import org.bidon.rendering.AdInfo
import org.bidon.sdk.stats.StatisticsCollector

/**
 * This ad as the renderer's telemetry labels it. The mediation SDK sets the auction id before it calls `load`, but the
 * getter throws when it has not, and a telemetry label must never fail a load.
 */
internal fun StatisticsCollector.adInfo(type: AdType): AdInfo {
    val auctionId = try {
        auctionId
    } catch (_: IllegalArgumentException) {
        null
    }
    return AdInfo(auctionId, type)
}
