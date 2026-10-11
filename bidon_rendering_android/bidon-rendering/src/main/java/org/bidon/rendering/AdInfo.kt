package org.bidon.rendering

/**
 * Which ad a renderer shows, as the host knows it. It only labels the renderer's telemetry events, so they can be
 * joined with the server's; nothing the renderer does depends on it.
 *
 * @param auctionId the id of the auction the ad won, or `null` when the host has none. It is sent to the server as
 * it is, so pass the auction's id and nothing else.
 */
class AdInfo(val auctionId: String?, val type: AdType)

/** The kind of ad: banner, interstitial or rewarded. */
enum class AdType {
    BANNER,
    INTERSTITIAL,
    REWARDED,
}
