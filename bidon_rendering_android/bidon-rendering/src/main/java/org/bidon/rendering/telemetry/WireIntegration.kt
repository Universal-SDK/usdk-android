package org.bidon.rendering.telemetry

/**
 * Which integration an event came from, as the two labels the wire carries: who hosts the renderer and who runs the
 * auction.
 */
internal enum class WireIntegration(val host: String, val auctionOwner: String) {
    DIRECT("publisher", "bidon"),
    MAX_CUSTOM("max_adapter", "bidon"),
    MAX_BIDDING("max_adapter", "max"),
}
