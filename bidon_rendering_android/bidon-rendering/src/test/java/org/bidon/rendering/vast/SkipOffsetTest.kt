package org.bidon.rendering.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `skipoffset` decides how long a user must watch before leaving. Read too short, the ad can be
 * skipped at once; not read at all, the DSP's general fallback replaces what the creative asked for.
 */
class SkipOffsetTest {

    @Test
    fun `a time reads as hours, minutes and seconds`() {
        assertEquals(SkipOffset.Time(5_000), SkipOffset.parse("00:00:05"))
        assertEquals(SkipOffset.Time(3_723_000), SkipOffset.parse("01:02:03"))
        assertEquals(SkipOffset.Time(5_000), SkipOffset.parse("0:0:5"))
    }

    @Test
    fun `a time keeps its milliseconds`() {
        assertEquals(SkipOffset.Time(5_500), SkipOffset.parse("00:00:05.500"))
        assertEquals(SkipOffset.Time(5_500), SkipOffset.parse("00:00:05.5"))
        assertEquals(SkipOffset.Time(5_050), SkipOffset.parse("00:00:05.05"))
        assertEquals(SkipOffset.Time(5_123), SkipOffset.parse("00:00:05.123"))
    }

    @Test
    fun `a percentage reads as a share of the video`() {
        assertEquals(SkipOffset.Percent(25.0), SkipOffset.parse("25%"))
        assertEquals(SkipOffset.Percent(12.5), SkipOffset.parse("12.5%"))
        assertEquals(SkipOffset.Percent(0.0), SkipOffset.parse("0%"))
        assertEquals(SkipOffset.Percent(100.0), SkipOffset.parse("100%"))
    }

    @Test
    fun `a bare number reads as seconds`() {
        // It was read as milliseconds, so "15" made the video skippable at once.
        assertEquals(SkipOffset.Time(15_000), SkipOffset.parse("15"))
        assertEquals(SkipOffset.Time(2_500), SkipOffset.parse("2.5"))
        assertEquals(SkipOffset.Time(0), SkipOffset.parse("0"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(SkipOffset.Time(5_000), SkipOffset.parse(" 00:00:05\n"))
        assertEquals(SkipOffset.Percent(25.0), SkipOffset.parse("\t25% "))
    }

    @Test
    fun `anything else is not a skip offset`() {
        listOf(
            "", " ", "abc", "%", "25 %", "-5%", "-5", "1,5%", "101%", "100.5%", "1000%",
            // Garbage in the time form used to read as zero, which made the video skippable at once.
            "aa:bb:cc", "00:60:00", "00:00:60", "1:2", "00:00:05.1234", "00:00:05.", "100:00:00",
            "25%%", "5s", "00:00:05 25%"
        ).forEach { value ->
            assertNull("\"$value\"", SkipOffset.parse(value))
        }
    }

    @Test
    fun `a percentage resolves against the video's duration`() {
        assertEquals(7_500L, SkipOffset.Percent(25.0).availableAtMs(30_000))
        assertEquals(0L, SkipOffset.Percent(0.0).availableAtMs(30_000))
        assertEquals(30_000L, SkipOffset.Percent(100.0).availableAtMs(30_000))
    }

    @Test
    fun `a percentage waits for the duration to be known`() {
        assertNull(SkipOffset.Percent(25.0).availableAtMs(0))
        assertNull(SkipOffset.Percent(25.0).availableAtMs(-1))
    }

    @Test
    fun `a time does not depend on the duration`() {
        assertEquals(5_000L, SkipOffset.Time(5_000).availableAtMs(0))
        assertEquals(5_000L, SkipOffset.Time(5_000).availableAtMs(30_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative time cannot be built`() {
        SkipOffset.Time(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a percentage above 100 cannot be built`() {
        SkipOffset.Percent(100.1)
    }
}
