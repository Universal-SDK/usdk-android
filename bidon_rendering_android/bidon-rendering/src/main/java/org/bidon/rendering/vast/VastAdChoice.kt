package org.bidon.rendering.vast

/**
 * Which `<Ad>` of a VAST response this player plays. It plays one ad per response, as VAST 4.3
 * allows a player without pod support: the first ad of the pod when there is one, else the first
 * stand-alone ad.
 */
internal object VastAdChoice {

    /**
     * @param sequences the `sequence` attribute of each `<Ad>` in document order, null when the ad
     *   has none or it is not a positive integer (a stand-alone ad).
     * @param podsAllowed false for a response reached through a wrapper whose `allowMultipleAds`
     *   is absent or false: then only a stand-alone ad may play. A response with a single ad plays
     *   it whatever its sequence, since one ad cannot be over-reported and a lone `sequence="1"`
     *   is common.
     * @return the index of the ad to play, or null when none may.
     */
    fun choose(sequences: List<Int?>, podsAllowed: Boolean): Int? {
        if (sequences.size == 1) return 0
        if (podsAllowed) {
            sequences.filterNotNull().minOrNull()?.let { return sequences.indexOf(it) }
        }
        return sequences.indexOf(null).takeIf { it >= 0 }
    }
}
