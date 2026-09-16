package org.bidon.rendering.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sends the buyer's impression trackers.
 *
 * When they are sent is the video screen's decision; what this checks is that every URL goes out,
 * and with the same macros filled in as the progress events, so a buyer reading `[ADPLAYHEAD]` or
 * `[CACHEBUSTING]` sees real values rather than the placeholder.
 */
class VastEventTrackerTest {

    private val sent = mutableListOf<String>()
    private val tracker = VastEventTracker { sent += it }

    @Test
    fun `every impression URL is sent once, with its macros filled in`() {
        tracker.onImpression(
            listOf(
                "https://dsp.example/impression?cb=[CACHEBUSTING]&ts=[TIMESTAMP]&r=[RANDOM]&at=[ADPLAYHEAD]",
                "https://verifier.example/impression"
            )
        )

        assertEquals(2, sent.size)
        assertTrue(sent[0], sent[0].startsWith("https://dsp.example/impression?cb="))
        assertTrue(sent[0], "[" !in sent[0])
        assertTrue(sent[0], sent[0].endsWith("&at=00:00:00"))
        assertEquals("https://verifier.example/impression", sent[1])
    }
}
