package org.bidon.rendering.telemetry

/**
 * The one way the renderer reports a telemetry event.
 *
 * Both calls return at once and never throw, whatever state telemetry is in, so they are safe on the ad path and on
 * the main thread.
 */
internal interface Telemetry {

    /** Hands [event] over to be stored. When telemetry cannot keep up, the event is dropped and counted. */
    fun emit(event: TelemetryEvent)

    /** Asks for everything stored so far to be forced to the storage device, when the app goes to the background. */
    fun sync()

    /** Telemetry that is switched off. */
    object Disabled : Telemetry {
        override fun emit(event: TelemetryEvent) = Unit
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
