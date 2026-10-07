package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * At most 20 events of one name in any 60 s (D-031), on a sliding window.
 *
 * "Any 60 s" is read as: at time `now` the window holds the admitted events with a timestamp `t` such that
 * `now - 60_000 < t <= now`. An event admitted at `t` therefore occupies a place until `t + 60_000`, exclusive: at
 * `t + 59_999` it still counts, at `t + 60_000` its place is free.
 */
class RateLimiterTest {

    private val clock = FakeClock()

    /** The limiter with the defaults of D-031: 20 per 60 s. */
    private val limiter = RateLimiter(clock)

    @Test
    fun `twenty events of one name pass and the twenty-first within the window is refused`() {
        repeat(20) { assertTrue("event ${it + 1}", limiter.tryAcquire("load")) }

        assertFalse(limiter.tryAcquire("load"))
    }

    @Test
    fun `a place stays taken until 60 s after the event that took it, and frees exactly then`() {
        clock.now = 1_000
        repeat(20) { assertTrue(limiter.tryAcquire("load")) }

        clock.now = 1_000 + 59_999
        assertFalse(limiter.tryAcquire("load"))

        clock.now = 1_000 + 60_000
        repeat(20) { assertTrue("event ${it + 1}", limiter.tryAcquire("load")) }
        assertFalse(limiter.tryAcquire("load"))
    }

    @Test
    fun `the window slides - only the places of events older than 60 s free up`() {
        clock.now = 0
        repeat(10) { assertTrue(limiter.tryAcquire("load")) }
        clock.now = 30_000
        repeat(10) { assertTrue(limiter.tryAcquire("load")) }
        assertFalse(limiter.tryAcquire("load"))

        // The first ten have left the window; the ten from 30 s are still in it.
        clock.now = 60_000
        repeat(10) { assertTrue("event ${it + 1}", limiter.tryAcquire("load")) }
        assertFalse(limiter.tryAcquire("load"))

        clock.now = 89_999
        assertFalse(limiter.tryAcquire("load"))

        clock.now = 90_000
        repeat(10) { assertTrue("event ${it + 1}", limiter.tryAcquire("load")) }
        assertFalse(limiter.tryAcquire("load"))
    }

    @Test
    fun `the oldest admitted event frees the first place, one at a time`() {
        for (i in 0 until 20) {
            clock.now = i * 1_000L
            assertTrue(limiter.tryAcquire("load"))
        }

        clock.now = 60_000 - 1
        assertFalse(limiter.tryAcquire("load"))
        clock.now = 60_000
        assertTrue(limiter.tryAcquire("load"))
        assertFalse(limiter.tryAcquire("load"))
        clock.now = 61_000 - 1
        assertFalse(limiter.tryAcquire("load"))
        clock.now = 61_000
        assertTrue(limiter.tryAcquire("load"))
        assertFalse(limiter.tryAcquire("load"))
    }

    @Test
    fun `names are limited independently`() {
        repeat(20) { assertTrue(limiter.tryAcquire("load")) }
        assertFalse(limiter.tryAcquire("load"))

        repeat(20) { assertTrue("event ${it + 1}", limiter.tryAcquire("render")) }
        assertFalse(limiter.tryAcquire("render"))
    }

    @Test
    fun `refused attempts do not use up places`() {
        clock.now = 0
        repeat(20) { assertTrue(limiter.tryAcquire("load")) }
        clock.now = 30_000
        repeat(100) { assertFalse(limiter.tryAcquire("load")) }

        // Had the refusals at 30 s taken places, nothing would pass until 90 s.
        clock.now = 60_000
        repeat(20) { assertTrue("event ${it + 1}", limiter.tryAcquire("load")) }
    }

    @Test
    fun `the limit and the window can be set`() {
        val small = RateLimiter(clock, maxPerWindow = 2, windowMillis = 1_000)

        assertTrue(small.tryAcquire("load"))
        assertTrue(small.tryAcquire("load"))
        assertFalse(small.tryAcquire("load"))
        clock.now = 999
        assertFalse(small.tryAcquire("load"))
        clock.now = 1_000
        assertTrue(small.tryAcquire("load"))
    }

    @Test
    fun `threads racing for one name get exactly twenty places between them`() {
        val admitted = AtomicInteger()
        val go = CountDownLatch(1)
        val threads = (1..8).map {
            thread {
                go.await()
                repeat(50) { if (limiter.tryAcquire("load")) admitted.incrementAndGet() }
            }
        }

        go.countDown()
        threads.forEach { it.join() }

        assertEquals(20, admitted.get())
    }

    /**
     * Any non-decreasing sequence of attempts, on one or two names: an attempt passes exactly when fewer than 20 events
     * of its name were admitted in the 60 s before it. So no window ever holds more than 20, and nothing is refused
     * while there is room. The steps are mostly bursts, so the limit is reached often; a run that never reaches it
     * proves nothing and fails.
     */
    @Test
    fun `an attempt is refused exactly when twenty of its name are already in the window`() {
        val step = Arb.bind(
            Arb.int(0..99),
            Arb.long(0L..50L),
            Arb.long(0L..70_000L),
            Arb.int(0..1),
        ) { roll, burst, gap, name -> (if (roll < 97) burst else gap) to "name$name" }
        var refusals = 0

        runBlocking {
            checkAll(Arb.list(step, 0..400)) { steps ->
                val clock = FakeClock()
                val limiter = RateLimiter(clock)
                val admittedAt = HashMap<String, MutableList<Long>>()

                for ((advance, name) in steps) {
                    clock.now += advance
                    val admitted = admittedAt.getOrPut(name) { ArrayList() }
                    val inWindow = admitted.count { it > clock.now - 60_000 }

                    val passed = limiter.tryAcquire(name)

                    assertEquals("$name at ${clock.now} with $inWindow in the window", inWindow < 20, passed)
                    if (passed) admitted += clock.now else refusals++
                    assertTrue(admitted.count { it > clock.now - 60_000 } <= 20)
                }
            }
        }

        assertTrue("no attempt was ever refused", refusals > 0)
    }

    private class FakeClock : MonotonicClock {
        @Volatile
        var now = 0L

        override fun elapsedMillis(): Long = now
    }
}
