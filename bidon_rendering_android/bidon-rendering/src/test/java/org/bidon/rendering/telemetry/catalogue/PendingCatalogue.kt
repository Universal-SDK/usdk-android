package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.EventName
import org.bidon.rendering.telemetry.catalogue.Conformance.Rule

/**
 * What we send on purpose before the catalogue holds it. Each entry names the ticket that removes it. An entry the
 * catalogue now accepts, or one no sample produces any more, fails `CatalogueConformanceTest`, so the list only shrinks.
 */
internal object PendingCatalogue {

    data class Entry(val event: String, val key: String, val rule: Rule, val removedBy: String)

    private val envelopeKeys = listOf("envelope.host", "envelope.auction_owner", "envelope.sdk_uptime_ms")

    /**
     * Every event the catalogue knows, each of which carries the three envelope fields it does not know yet. An event
     * the catalogue does not know is judged on its name alone, so it has no field entries.
     */
    private val integrationFields = EventName.entries.map { it.wireName }
        .filter { it in CatalogueTestSupport.vendoredCatalogue().clientEventNames }
        .flatMap { event ->
            envelopeKeys.map { key ->
                Entry(
                    event, key, Rule.UNKNOWN_FIELD,
                    removedBy = "Telemetry: envelope, ad-context and session fields survive ingest on staging",
                )
            }
        }

    val entries = integrationFields + listOf(
        Entry(
            "sdk_crashed", "envelope.event_name", Rule.UNKNOWN_EVENT,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "render_crashed", "did_crash", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "render_crashed", "was_shown", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: crash, drop and exception-class data survive ingest on staging",
        ),
        Entry(
            "render_crashed", "renderer_priority_at_exit", Rule.UNKNOWN_FIELD,
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
        Entry(
            "ad_show_failed", "error_domain", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: show, WebView, video and init failure fields survive ingest on staging",
        ),
        Entry(
            "ad_show_failed", "external_error_code", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: show, WebView, video and init failure fields survive ingest on staging",
        ),
        Entry(
            "ad_show_failed", "external_error_source", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: show, WebView, video and init failure fields survive ingest on staging",
        ),
        Entry(
            "ad_show_failed", "exception_class", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: show, WebView, video and init failure fields survive ingest on staging",
        ),
        Entry(
            "ad_show_failed", "exception_cause_class", Rule.UNKNOWN_FIELD,
            removedBy = "Telemetry: show, WebView, video and init failure fields survive ingest on staging",
        ),
    )
}
