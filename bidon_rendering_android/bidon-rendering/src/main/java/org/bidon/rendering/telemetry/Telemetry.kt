package org.bidon.rendering.telemetry

/**
 * The one way the renderer reports a telemetry event.
 *
 * Both calls return at once and never throw, whatever state telemetry is in, so they are safe on the ad path and on
 * the main thread.
 */
internal interface Telemetry {

    /**
     * Captures an event named [eventName] of [eventClass] if telemetry keeps it: [capture] runs on the caller's thread
     * with the rate it was kept at, and is never called otherwise. The captured event's own class decides where it is
     * stored, so callers pass the same class to both.
     */
    fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent)

    /**
     * Asks for everything stored so far to be forced to the storage device, and then sent, when the app goes to the
     * background.
     */
    fun sync()

    /** Telemetry that is switched off. */
    object Disabled : Telemetry {
        override fun emit(eventName: String, eventClass: EventClass, capture: (SamplingRate) -> TelemetryEvent) = Unit
        override fun sync() = Unit
    }
}

/**
 * An event as the renderer captured it. It is built on the caller's thread from values taken at that moment, its
 * timestamp included, and does not change afterwards. [encode] turns it into bytes later, on the telemetry thread, so
 * the caller never pays for serialisation.
 */
internal fun interface TelemetryEvent {

    fun encode(): ByteArray

    /** Decides how long the event may wait on the device and what may evict it. */
    val eventClass: EventClass get() = EventClass.DIAGNOSTIC
}
