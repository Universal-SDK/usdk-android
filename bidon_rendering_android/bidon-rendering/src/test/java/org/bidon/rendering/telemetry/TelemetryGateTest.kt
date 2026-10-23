package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The gate's checks run in the order of D-031: the kill switch, the allow-list, sampling, the rate limit. Only the
 * rate limit counts what it refuses, and only events that reached it use up its places.
 *
 * The session is `abc`, whose position is about 0.728: kept at 0.8, dropped at 0.5.
 */
class TelemetryGateTest {

    private val clock = MonotonicClock { 0L }
    private val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(clock))

    @Test
    fun `by default every event passes at rate 1`() {
        assertEquals(SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC))
        assertEquals(SamplingRate.ALL, gate.admit("failed", EventClass.ERROR))
    }

    @Test
    fun `a kept event carries the effective rate`() {
        gate.config = TelemetryConfig(samplingRate = 0.8, eventRates = mapOf("render" to 0.9))

        assertEquals(0.8, gate.admit("load", EventClass.DIAGNOSTIC)?.fraction)
        assertEquals(0.9, gate.admit("render", EventClass.DIAGNOSTIC)?.fraction)
    }

    @Test
    fun `the twenty-first event of a name in the window is refused and counted, errors included`() {
        repeat(20) { assertEquals(SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC)) }
        repeat(20) { assertEquals(SamplingRate.ALL, gate.admit("failed", EventClass.ERROR)) }

        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
        assertNull(gate.admit("failed", EventClass.ERROR))
        assertNull(gate.admit("failed", EventClass.ERROR))

        assertEquals(3L, gate.takeRateLimited())
    }

    @Test
    fun `the rate limit does not change the rate written on kept events`() {
        gate.config = TelemetryConfig(samplingRate = 0.8)

        repeat(20) { assertEquals(0.8, gate.admit("load", EventClass.DIAGNOSTIC)?.fraction) }
        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
    }

    @Test
    fun `taking the rate-limited count returns it and resets it`() {
        assertEquals(0L, gate.takeRateLimited())
        repeat(25) { gate.admit("load", EventClass.DIAGNOSTIC) }

        assertEquals(5L, gate.takeRateLimited())
        assertEquals(0L, gate.takeRateLimited())

        gate.admit("load", EventClass.DIAGNOSTIC)
        assertEquals(1L, gate.takeRateLimited())
    }

    @Test
    fun `switched off, nothing passes, errors included, and nothing is counted or uses a place`() {
        gate.config = TelemetryConfig(enabled = false)

        repeat(30) {
            assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
            assertNull(gate.admit("failed", EventClass.ERROR))
        }
        assertEquals(0L, gate.takeRateLimited())

        gate.config = TelemetryConfig.DEFAULT
        assertTwentyPlacesLeft("load", EventClass.DIAGNOSTIC)
        assertTwentyPlacesLeft("failed", EventClass.ERROR)
    }

    @Test
    fun `switched off beats every other setting`() {
        gate.config = TelemetryConfig(
            enabled = false,
            samplingRate = 1.0,
            eventRates = mapOf("load" to 1.0),
            allowedEvents = setOf("load"),
        )

        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
        assertNull(gate.admit("load", EventClass.ERROR))
    }

    @Test
    fun `no allow-list lets every name through`() {
        gate.config = TelemetryConfig(allowedEvents = null)

        assertEquals(SamplingRate.ALL, gate.admit("anything", EventClass.DIAGNOSTIC))
        assertEquals(SamplingRate.ALL, gate.admit("anything else", EventClass.ERROR))
    }

    @Test
    fun `an allow-list lets only its names through, errors included`() {
        gate.config = TelemetryConfig(allowedEvents = setOf("load", "failed"))

        assertEquals(SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC))
        assertEquals(SamplingRate.ALL, gate.admit("failed", EventClass.ERROR))
        assertNull(gate.admit("render", EventClass.DIAGNOSTIC))
        assertNull(gate.admit("crashed", EventClass.ERROR))
    }

    @Test
    fun `an empty allow-list lets nothing through, errors included`() {
        gate.config = TelemetryConfig(allowedEvents = emptySet())

        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
        assertNull(gate.admit("failed", EventClass.ERROR))
    }

    @Test
    fun `names the allow-list refuses are not counted and use no place`() {
        gate.config = TelemetryConfig(allowedEvents = setOf("other"))
        repeat(30) {
            assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
            assertNull(gate.admit("failed", EventClass.ERROR))
        }
        assertEquals(0L, gate.takeRateLimited())

        gate.config = TelemetryConfig.DEFAULT
        assertTwentyPlacesLeft("load", EventClass.DIAGNOSTIC)
        assertTwentyPlacesLeft("failed", EventClass.ERROR)
    }

    @Test
    fun `sampled-out events are not counted and use no place`() {
        gate.config = TelemetryConfig(samplingRate = 0.5)
        repeat(30) { assertNull(gate.admit("load", EventClass.DIAGNOSTIC)) }
        assertEquals(0L, gate.takeRateLimited())

        gate.config = TelemetryConfig.DEFAULT
        assertTwentyPlacesLeft("load", EventClass.DIAGNOSTIC)
    }

    @Test
    fun `a name switched off by its rate is not counted and uses no place`() {
        gate.config = TelemetryConfig(eventRates = mapOf("load" to 0.0))
        repeat(30) { assertNull(gate.admit("load", EventClass.DIAGNOSTIC)) }
        assertEquals(0L, gate.takeRateLimited())

        gate.config = TelemetryConfig.DEFAULT
        assertTwentyPlacesLeft("load", EventClass.DIAGNOSTIC)
    }

    @Test
    fun `a new config takes effect on the next admit`() {
        assertEquals(SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC))

        gate.config = TelemetryConfig(samplingRate = 0.5)
        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))

        gate.config = TelemetryConfig(samplingRate = 0.8)
        assertEquals(0.8, gate.admit("load", EventClass.DIAGNOSTIC)?.fraction)

        gate.config = TelemetryConfig(allowedEvents = setOf("render"))
        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))

        gate.config = TelemetryConfig(enabled = false)
        assertNull(gate.admit("render", EventClass.DIAGNOSTIC))

        gate.config = TelemetryConfig.DEFAULT
        assertEquals(SamplingRate.ALL, gate.admit("render", EventClass.DIAGNOSTIC))
    }

    @Test
    fun `a config is replaced whole - settings of the old one do not linger`() {
        gate.config = TelemetryConfig(samplingRate = 0.5, eventRates = mapOf("render" to 0.5), allowedEvents = setOf("x"))
        gate.config = TelemetryConfig(samplingRate = 0.9)

        assertEquals(0.9, gate.admit("load", EventClass.DIAGNOSTIC)?.fraction)
        assertEquals(0.9, gate.admit("render", EventClass.DIAGNOSTIC)?.fraction)
    }

    @Test
    fun `a global rate outside 0 to 1 switches diagnostics off, uncounted and using no place, and errors still pass`() {
        for (rate in INVALID_RATES) {
            val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(clock))
            gate.config = TelemetryConfig(samplingRate = rate)

            repeat(30) { assertNull("rate $rate", gate.admit("load", EventClass.DIAGNOSTIC)) }
            assertEquals("rate $rate", SamplingRate.ALL, gate.admit("failed", EventClass.ERROR))
            assertEquals("rate $rate", 0L, gate.takeRateLimited())

            gate.config = TelemetryConfig.DEFAULT
            assertTwentyPlacesLeft(gate, "load", EventClass.DIAGNOSTIC)
        }
    }

    @Test
    fun `a per-name rate outside 0 to 1 switches that name off, uncounted and using no place, and errors still pass`() {
        for (rate in INVALID_RATES) {
            val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(clock))
            gate.config = TelemetryConfig(samplingRate = 1.0, eventRates = mapOf("load" to rate, "failed" to rate))

            repeat(30) { assertNull("rate $rate", gate.admit("load", EventClass.DIAGNOSTIC)) }
            assertEquals("rate $rate", SamplingRate.ALL, gate.admit("failed", EventClass.ERROR))
            assertEquals("rate $rate", SamplingRate.ALL, gate.admit("render", EventClass.DIAGNOSTIC))
            assertEquals("rate $rate", 0L, gate.takeRateLimited())

            gate.config = TelemetryConfig.DEFAULT
            assertTwentyPlacesLeft(gate, "load", EventClass.DIAGNOSTIC)
        }
    }

    @Test
    fun `one admit reads one config, even when the config changes during the admit`() {
        // Consulting the allow-list replaces the config with one that refuses at every check. An admit that read the
        // config once still decides on the first one, and keeps the event at 1.
        val refusing = TelemetryConfig(enabled = false, samplingRate = 0.0, allowedEvents = emptySet())
        var consulted = 0
        val swapping = object : AbstractSet<String>() {
            override val size = 1
            override fun iterator() = listOf("load").iterator()
            override fun contains(element: String): Boolean {
                consulted++
                gate.config = refusing
                return element == "load"
            }
        }
        gate.config = TelemetryConfig(samplingRate = 1.0, allowedEvents = swapping)

        val rate = gate.admit("load", EventClass.DIAGNOSTIC)

        assertTrue("the allow-list was never consulted", consulted > 0)
        assertEquals(SamplingRate.ALL, rate)
    }

    @Test
    fun `one admit reads one config, never parts of two, while another thread replaces it`() {
        // Each refuses every diagnostic, a by its allow-list and b by its rate. Only a mix of a's rate and b's
        // allow-list would let one through.
        val a = TelemetryConfig(samplingRate = 1.0, allowedEvents = emptySet())
        val b = TelemetryConfig(samplingRate = 0.0, allowedEvents = null)
        gate.config = a
        val stop = AtomicBoolean()
        val flips = AtomicLong()
        val flipper = thread {
            while (!stop.get()) {
                gate.config = b
                gate.config = a
                flips.incrementAndGet()
            }
        }

        var admitted = 0
        try {
            repeat(2_000_000) { if (gate.admit("load", EventClass.DIAGNOSTIC) != null) admitted++ }
        } finally {
            stop.set(true)
            flipper.join()
        }

        assertTrue("the config was never replaced", flips.get() > 0)
        assertEquals(0, admitted)
    }

    // Counted events: never sampled, never rate limited, but still behind the kill switch and the allow-list.

    @Test
    fun `a counted event passes at rate 1 whatever the global sampling rate says`() {
        for (rate in listOf(0.01, 0.5, 0.0, Double.NaN, -0.5, 10.0)) {
            val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(clock))
            gate.config = TelemetryConfig(samplingRate = rate)

            assertEquals("rate $rate", SamplingRate.ALL, gate.admit("ad_impression", EventClass.COUNTED))
        }
    }

    @Test
    fun `a counted event passes at rate 1 whatever the rate for its name says, a session above that rate included`() {
        for (rate in listOf(0.01, 0.5, 0.0, Double.NaN, -0.5, 10.0)) {
            val gate = TelemetryGate(SessionId.of("abc")!!, RateLimiter(clock))
            gate.config = TelemetryConfig(samplingRate = 1.0, eventRates = mapOf("ad_impression" to rate))

            assertEquals("rate $rate", SamplingRate.ALL, gate.admit("ad_impression", EventClass.COUNTED))
        }
    }

    @Test
    fun `a diagnostic of the same name is still sampled out where a counted event passes`() {
        gate.config = TelemetryConfig(samplingRate = 0.01, eventRates = mapOf("ad_impression" to 0.01))

        assertEquals(SamplingRate.ALL, gate.admit("ad_impression", EventClass.COUNTED))
        assertNull(gate.admit("ad_impression", EventClass.DIAGNOSTIC))
    }

    @Test
    fun `counted events are not rate limited, not counted as limited and use no place`() {
        repeat(25) { assertEquals("event ${it + 1}", SamplingRate.ALL, gate.admit("ad_impression", EventClass.COUNTED)) }

        assertEquals(0L, gate.takeRateLimited())
        assertTwentyPlacesLeft("ad_impression", EventClass.DIAGNOSTIC)
    }

    @Test
    fun `a diagnostic is still capped at twenty a minute next to a flood of counted events`() {
        repeat(25) { gate.admit("ad_impression", EventClass.COUNTED) }
        repeat(20) { assertEquals(SamplingRate.ALL, gate.admit("load", EventClass.DIAGNOSTIC)) }

        repeat(25) { gate.admit("ad_impression", EventClass.COUNTED) }

        assertNull(gate.admit("load", EventClass.DIAGNOSTIC))
        assertEquals(1L, gate.takeRateLimited())
    }

    @Test
    fun `switched off, a counted event is refused`() {
        gate.config = TelemetryConfig(enabled = false)

        repeat(30) { assertNull(gate.admit("ad_impression", EventClass.COUNTED)) }
        assertEquals(0L, gate.takeRateLimited())
    }

    @Test
    fun `switched off beats a counted event's own rate`() {
        gate.config = TelemetryConfig(enabled = false, samplingRate = 1.0, eventRates = mapOf("ad_impression" to 1.0))

        assertNull(gate.admit("ad_impression", EventClass.COUNTED))
    }

    @Test
    fun `an allow-list without its name refuses a counted event, and one with it lets it through`() {
        gate.config = TelemetryConfig(allowedEvents = setOf("load"))
        assertNull(gate.admit("ad_impression", EventClass.COUNTED))

        gate.config = TelemetryConfig(allowedEvents = emptySet())
        assertNull(gate.admit("ad_impression", EventClass.COUNTED))

        gate.config = TelemetryConfig(samplingRate = 0.01, allowedEvents = setOf("ad_impression"))
        assertEquals(SamplingRate.ALL, gate.admit("ad_impression", EventClass.COUNTED))
    }

    /** Exactly 20 more events of [name] pass, and the next one is refused. */
    private fun assertTwentyPlacesLeft(name: String, eventClass: EventClass) = assertTwentyPlacesLeft(gate, name, eventClass)

    private fun assertTwentyPlacesLeft(gate: TelemetryGate, name: String, eventClass: EventClass) {
        repeat(20) { assertEquals("event ${it + 1}", SamplingRate.ALL, gate.admit(name, eventClass)) }
        assertNull(gate.admit(name, eventClass))
    }

    private companion object {
        /** Not in (0, 1]: no event can carry them, so each switches its name off for diagnostics. */
        val INVALID_RATES = listOf(Double.NaN, -0.5, 0.0, 1.0000001, 10.0)
    }
}
