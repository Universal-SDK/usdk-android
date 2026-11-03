package org.bidon.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The uptime source of the telemetry envelope, against an elapsed-time reading the test moves by hand. */
class ElapsedUptimeClockTest {

    private var elapsed = 5_000L

    @Test
    fun `the uptime is the reading minus the reading at creation`() {
        val clock = ElapsedUptimeClock { elapsed }

        elapsed = 7_500L

        assertEquals(2_500L, clock.uptimeMillis())
    }

    @Test
    fun `the uptime is zero when read at the moment of creation`() {
        assertEquals(0L, ElapsedUptimeClock { elapsed }.uptimeMillis())
    }

    @Test
    fun `the origin is read once, at creation, and only that reading is the origin`() {
        val readings = mutableListOf<Long>()
        val clock = ElapsedUptimeClock { elapsed.also { readings += it } }
        assertEquals(1, readings.size)

        elapsed = 6_000L
        clock.uptimeMillis()
        elapsed = 9_000L

        assertEquals(4_000L, clock.uptimeMillis())
        assertEquals(5_000L, readings.first())
    }

    @Test
    fun `reading never moves the origin`() {
        val clock = ElapsedUptimeClock { elapsed }

        val readings = listOf(1_000L, 1_000L, 3_000L, 3_500L).map { passed ->
            elapsed = 5_000L + passed
            clock.uptimeMillis()
        }

        assertEquals(listOf(1_000L, 1_000L, 3_000L, 3_500L), readings)
    }

    @Test
    fun `a clock created later does not move an earlier one`() {
        val first = ElapsedUptimeClock { elapsed }
        elapsed = 8_000L
        val second = ElapsedUptimeClock { elapsed }
        elapsed = 9_000L

        assertEquals(4_000L, first.uptimeMillis())
        assertEquals(1_000L, second.uptimeMillis())
    }

    @Test
    fun `an elapsed clock that only moves forward gives an uptime that only moves forward`() {
        val clock = ElapsedUptimeClock { elapsed }

        val uptimes = (1..100).map {
            elapsed += it % 7
            clock.uptimeMillis()
        }

        assertEquals(uptimes.sorted(), uptimes)
        assertTrue(uptimes.all { it >= 0 })
    }
}
