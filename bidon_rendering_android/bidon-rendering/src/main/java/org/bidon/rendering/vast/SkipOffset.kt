package org.bidon.rendering.vast

/**
 * How far into a linear video Skip becomes available, as the VAST `skipoffset` attribute sets it:
 * a fixed time, or a share of the video's duration.
 */
sealed interface SkipOffset {

    /**
     * When Skip becomes available in a video lasting [durationMs], in milliseconds from its start.
     * Null while the duration is unknown, since a share of an unknown length is not a time yet.
     */
    fun availableAtMs(durationMs: Long): Long?

    data class Time(val ms: Long) : SkipOffset {
        init {
            require(ms >= 0) { "ms must not be negative, was $ms" }
        }

        override fun availableAtMs(durationMs: Long): Long = ms
    }

    data class Percent(val value: Double) : SkipOffset {
        init {
            require(value in 0.0..100.0) { "value must be within 0..100, was $value" }
        }

        override fun availableAtMs(durationMs: Long): Long? =
            if (durationMs > 0) (durationMs * value / 100).toLong() else null
    }

    companion object {

        // HH:MM:SS or HH:MM:SS.mmm, the time forms VAST allows.
        private val TIME = Regex("""(\d{1,2}):(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?""")

        // n%, the percentage form VAST allows. Read the same whatever the device locale.
        private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

        // A bare number is not valid VAST, but widely used players read it as seconds, so a creative
        // tested there skips at the same moment here.
        private val SECONDS = Regex("""(\d{1,5})(?:\.(\d{1,3}))?""")

        /**
         * Reads a `skipoffset` value. Returns null for anything that is not one, including minutes
         * or seconds of 60 and more and percentages above 100, so the caller's fallback applies.
         */
        internal fun parse(value: String): SkipOffset? {
            val text = value.trim()

            TIME.matchEntire(text)?.destructured?.let { (hours, minutes, seconds, fraction) ->
                if (minutes.toInt() >= 60 || seconds.toInt() >= 60) return null
                return Time(
                    hours.toLong() * 3_600_000 +
                        minutes.toLong() * 60_000 +
                        seconds.toLong() * 1_000 +
                        fraction.toMillis()
                )
            }

            PERCENT.matchEntire(text)?.let { match ->
                return match.groupValues[1].toDouble().takeIf { it <= 100 }?.let(::Percent)
            }

            SECONDS.matchEntire(text)?.destructured?.let { (seconds, fraction) ->
                return Time(seconds.toLong() * 1_000 + fraction.toMillis())
            }

            return null
        }

        // "5" is 500 ms and "05" is 50 ms: the digits after the decimal point, not a count of ms.
        private fun String.toMillis(): Long = if (isEmpty()) 0 else padEnd(3, '0').toLong()
    }
}
