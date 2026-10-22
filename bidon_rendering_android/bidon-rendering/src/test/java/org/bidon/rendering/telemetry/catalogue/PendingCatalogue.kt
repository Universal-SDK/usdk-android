package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.catalogue.Conformance.Rule

/**
 * What we send on purpose before the catalogue holds it. Each entry names the ticket that removes it. An entry the
 * catalogue now accepts, or one no sample produces any more, fails `CatalogueConformanceTest`, so the list only shrinks.
 */
internal object PendingCatalogue {

    data class Entry(val event: String, val key: String, val rule: Rule, val removedBy: String)

    val entries = listOf(
        Entry(
            "creative_rendered", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: every impression is recorded as `ad_impression`, never sampled, kept a day",
        ),
        Entry(
            "creative_load_failed", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: a load failure arrives as `ad_load_failed` with a stable code, owner, stage and " +
                "exception class",
        ),
        Entry(
            "render_process_gone", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: a dying WebView arrives as `render_crashed`, telling a crash from a system kill",
        ),
        Entry(
            "render_crashed", "crashed_on_main_thread", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: a crash in our code arrives as `sdk_crashed`, including crashes inside our coroutines",
        ),
        Entry(
            "render_crashed", "exceptions", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: a crash in our code arrives as `sdk_crashed`, including crashes inside our coroutines",
        ),
    )
}
