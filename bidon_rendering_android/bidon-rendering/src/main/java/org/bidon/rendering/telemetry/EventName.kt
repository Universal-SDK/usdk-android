package org.bidon.rendering.telemetry

/**
 * Every event the SDK emits, by its name on the wire. Each one has a checked-in sample that is held against the
 * server's catalogue, so a new event starts here.
 */
internal enum class EventName(val wireName: String) {
    CREATIVE_RENDERED("creative_rendered"),
    CREATIVE_LOAD_FAILED("creative_load_failed"),
    RENDER_PROCESS_GONE("render_process_gone"),
    RENDER_CRASHED("render_crashed"),
}
