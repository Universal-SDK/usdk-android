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
            "render_process_gone", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: a dying WebView arrives as `render_crashed`, telling a crash from a system kill",
        ),
        Entry(
            "sdk_crashed", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "ad_load_failed", "external_error_code", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "ad_load_failed", "external_error_source", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "ad_load_failed", "exception_class", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "ad_load_failed", "exception_cause_class", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
    )
}
