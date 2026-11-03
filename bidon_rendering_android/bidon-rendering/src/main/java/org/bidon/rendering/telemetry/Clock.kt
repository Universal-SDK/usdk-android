package org.bidon.rendering.telemetry

/**
 * Wall-clock time in Unix milliseconds, behind an interface so tests can move it in either direction.
 */
internal fun interface Clock {

    fun currentTimeMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Time that only moves forward, in milliseconds from an arbitrary origin, for measuring waits. Unlike [Clock] it does
 * not jump when the user or the network changes the device's time.
 */
internal fun interface MonotonicClock {

    fun elapsedMillis(): Long

    companion object {
        val System: MonotonicClock = MonotonicClock { java.lang.System.nanoTime() / 1_000_000 }
    }
}

/**
 * Milliseconds since telemetry started, never decreasing. Unlike [Clock] it ignores what the user or the network does
 * to the device's time, so it orders the events of one process even when the wall clock is wrong.
 */
internal fun interface UptimeClock {

    fun uptimeMillis(): Long
}
