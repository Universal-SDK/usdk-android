package org.bidon.rendering.telemetry

import java.net.MalformedURLException
import java.net.URL

/**
 * Where telemetry is sent, and who it is sent as: the values the ingest contract requires on every batch.
 *
 * Only made by [of], which refuses values the request could not carry, so sending never fails on them.
 *
 * @property url the full address of the ingest endpoint.
 * @property appKey the publisher's app key.
 * @property bundle the host app's package name.
 * @property sdkVersion the version sent in the `X-Bidon-Version` header.
 */
internal class TelemetryEndpoint private constructor(
    val url: URL,
    val appKey: String,
    val bundle: String,
    val sdkVersion: String,
) {

    companion object {

        /** Longer values are not identifiers, and would crowd events out of a batch. */
        const val MAX_VALUE_LENGTH = 256

        /**
         * The endpoint, or null when [url] is not an http or https URL, or any other value is blank, longer than
         * [MAX_VALUE_LENGTH], or, for [sdkVersion], not printable ASCII, which an HTTP header could not carry.
         */
        fun of(url: String, appKey: String, bundle: String, sdkVersion: String): TelemetryEndpoint? {
            if (listOf(appKey, bundle, sdkVersion).any { it.isBlank() || it.length > MAX_VALUE_LENGTH }) return null
            if (sdkVersion.any { it !in ' '..'~' }) return null
            val parsed = try {
                URL(url)
            } catch (_: MalformedURLException) {
                return null
            }
            if (parsed.protocol != "https" && parsed.protocol != "http") return null
            return TelemetryEndpoint(parsed, appKey, bundle, sdkVersion)
        }
    }
}
