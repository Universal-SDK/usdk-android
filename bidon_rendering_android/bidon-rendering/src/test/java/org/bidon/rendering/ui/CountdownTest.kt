package org.bidon.rendering.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The close countdown shows whole seconds and ticks exactly when the number changes, so it never
 * shows 0 while the button is still locked, nor 1 once it is usable.
 */
class CountdownTest {

    @Test
    fun `partial seconds round up`() {
        assertEquals(5, Countdown.secondsLeft(5_000))
        assertEquals(5, Countdown.secondsLeft(4_300))
        assertEquals(1, Countdown.secondsLeft(1))
        assertEquals(2, Countdown.secondsLeft(1_001))
    }

    @Test
    fun `the next tick lands when the shown number changes`() {
        assertEquals(1_000L, Countdown.untilNextTick(5_000))
        assertEquals(300L, Countdown.untilNextTick(4_300))
        assertEquals(1L, Countdown.untilNextTick(1_001))
        assertEquals(1_000L, Countdown.untilNextTick(1_000))
        assertEquals(1L, Countdown.untilNextTick(1))
    }

    @Test
    fun `ticking through a whole countdown shows every second once and ends at zero`() {
        var remaining = 30_000L
        val shown = mutableListOf<Int>()
        while (remaining > 0) {
            shown += Countdown.secondsLeft(remaining)
            remaining -= Countdown.untilNextTick(remaining)
        }
        assertEquals((30 downTo 1).toList(), shown)
        assertEquals(0L, remaining)
    }
}
