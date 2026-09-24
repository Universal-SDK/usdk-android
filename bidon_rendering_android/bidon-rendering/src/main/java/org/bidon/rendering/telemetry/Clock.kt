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
