package org.bidon.rendering.telemetry

/**
 * Every event the SDK emits, by its name on the wire. Each one has a checked-in sample that is held against the
 * server's catalogue, so a new event starts here.
 */
internal enum class EventName(val wireName: String) {
    AD_SHOW_REQUESTED("ad_show_requested"),
    AD_SHOW_FAILED("ad_show_failed"),
    AD_IMPRESSION("ad_impression"),
    AD_LOAD_FAILED("ad_load_failed"),
    RENDER_CRASHED("render_crashed"),
    SDK_CRASHED("sdk_crashed"),
}
