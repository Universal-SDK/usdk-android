package org.bidon.rendering

/**
 * Which ad a renderer shows, as the host knows it. It only labels the renderer's telemetry events, so they can be
 * joined with the server's; nothing the renderer does depends on it.
 *
 * @param auctionId the id of the auction the ad won, or `null` when the host has none. It is sent to the server as
 * it is, so pass the auction's id and nothing else.
 * @param integration which integration created the renderer, or `null` when the host does not say, in which case the
 * events carry no integration labels rather than a guessed one. It is read when the renderer is created. Events that
 * belong to no ad, such as a crash, carry the integration of the most recently created renderer.
 */
class AdInfo @JvmOverloads constructor(val auctionId: String?, val type: AdType, val integration: Integration? = null)

/**
 * Which integration created a renderer. It labels the renderer's telemetry events; nothing else depends on it.
 *
 * More values may be added in later versions, so a `when` over it should not rely on being exhaustive.
 */
enum class Integration {
    /** The publisher's app uses the BidOn SDK directly, and BidOn runs the auction. */
    DIRECT,

    /** A MAX custom adapter hosts the renderer, and BidOn runs the auction. */
    MAX_CUSTOM,

    /** A MAX custom adapter hosts the renderer, and MAX runs the auction. */
    MAX_BIDDING,
}

/** The kind of ad: banner, interstitial or rewarded. */
enum class AdType {
    BANNER,
    INTERSTITIAL,
    REWARDED,
}
