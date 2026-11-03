package org.bidon.rendering.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Sends the buyer's impression and click trackers.
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

    @Test
    fun `every click tracker is sent on each click, at the playhead of that click`() {
        val clickTrackers = listOf(
            "https://dsp.example/click?at=[ADPLAYHEAD]",
            "https://verifier.example/click"
        )

        tracker.onProgress(0.4f, 30_000, 12_500, emptyList())
        tracker.onClick(clickTrackers)
        tracker.onClick(clickTrackers)

        assertEquals(
            listOf(
                "https://dsp.example/click?at=00:00:12",
                "https://verifier.example/click",
                "https://dsp.example/click?at=00:00:12",
                "https://verifier.example/click"
            ),
            sent
        )
    }

    @Test
    fun `the playhead is written in ASCII digits whatever the device language`() {
        val deviceLocale = Locale.getDefault()
        // Persian formats numbers with its own digits by default, and so do Arabic and Bengali.
        Locale.setDefault(Locale.forLanguageTag("fa"))
        try {
            tracker.onProgress(0.4f, 30_000, 12_500, emptyList())
            tracker.onClick(listOf("https://dsp.example/click?at=[ADPLAYHEAD]"))
        } finally {
            Locale.setDefault(deviceLocale)
        }

        assertEquals(listOf("https://dsp.example/click?at=00:00:12"), sent)
    }
}
