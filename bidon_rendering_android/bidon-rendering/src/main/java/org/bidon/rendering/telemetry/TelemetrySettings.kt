package org.bidon.rendering.telemetry

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the server says about telemetry, in two parts that are judged and kept apart: where to send, and the switches.
 * Each part is either usable or absent; absent is not the same as the defaults, it means "keep what is in force".
 */
internal data class TelemetrySettings(
    val endpoint: TelemetryEndpoint?,
    val config: TelemetryConfig?,
) {
    /** These settings, with each absent part taken from [older]. */
    fun over(older: TelemetrySettings): TelemetrySettings =
        TelemetrySettings(endpoint ?: older.endpoint, config ?: older.config)

    companion object {
        val NONE = TelemetrySettings(endpoint = null, config = null)
    }
}

/**
 * Reads the `telemetry` block of the adapter's `/v2/config` section:
 *
 * ```json
 * "telemetry": {
 *   "url": "https://<ingest host>/v2/telemetry",
 *   "app_key": "<publisher app key>",
 *   "enabled": true,
 *   "sample_rate": 0.25,
 *   "event_rates": { "ad_loaded": 0.05 },
 *   "event_names": ["..."]
 * }
 * ```
 *
 * Tolerant: a missing block, missing keys and unknown keys are fine. The endpoint part needs an `https` `url` and an
 * app key (the block's, else [fallbackAppKey]) that [TelemetryEndpoint.of] accepts. The switches part exists when the
 * block has at least one of `enabled`, `sample_rate`, `event_rates`, `event_names`; a key left out takes its
 * [TelemetryConfig] default. One malformed switch (`enabled` not a boolean, a rate not a number in [0, 1],
 * `event_rates` not an object of such rates, `event_names` not an array of strings) makes the whole switches part
 * absent, never half of it. The two parts are judged alone: a bad switch does not cost a good endpoint.
 */
internal object TelemetryBlock {

    /** The settings in [block], or [TelemetrySettings.NONE] when there is no block. */
    fun parse(block: JSONObject?, bundle: String, sdkVersion: String, fallbackAppKey: String?): TelemetrySettings {
        block ?: return TelemetrySettings.NONE
        val appKey = (block.opt("app_key") as? String)?.takeIf { it.isNotBlank() } ?: fallbackAppKey
        return TelemetrySettings(endpoint(block.opt("url") as? String, appKey, bundle, sdkVersion), switches(block))
    }

    /** The switches part of [json], or `null` when it has no switch or a malformed one. */
    fun switches(json: JSONObject): TelemetryConfig? {
        if (SWITCHES.none(json::has)) return null
        val enabled = if (json.has("enabled")) json.get("enabled") as? Boolean ?: return null else true
        val samplingRate = if (json.has("sample_rate")) rate(json.get("sample_rate")) ?: return null else 1.0
        val eventRates = if (json.has("event_rates")) rates(json.get("event_rates")) ?: return null else emptyMap()
        val allowedEvents = if (json.has("event_names")) names(json.get("event_names")) ?: return null else null
        return TelemetryConfig(enabled, samplingRate, eventRates, allowedEvents)
    }

    /**
     * The endpoint for these values, or `null` when [url] is not `https` or [TelemetryEndpoint.of] refuses them. Plain
     * http is refused because the ingest redirects it, and a redirected batch is lost.
     */
    fun endpoint(url: String?, appKey: String?, bundle: String, sdkVersion: String): TelemetryEndpoint? {
        if (url == null || appKey == null) return null
        return TelemetryEndpoint.of(url, appKey, bundle, sdkVersion)?.takeIf { it.url.protocol == "https" }
    }

    private val SWITCHES = listOf("enabled", "sample_rate", "event_rates", "event_names")

    /** A rate as a fraction in [0, 1], or `null` for anything else. An infinite number or NaN is outside the range. */
    private fun rate(value: Any): Double? = (value as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }

    /** An object of rates, or `null` when it is not an object or holds anything but rates. */
    private fun rates(value: Any): Map<String, Double>? {
        val json = value as? JSONObject ?: return null
        val rates = HashMap<String, Double>()
        for (name in json.keys()) rates[name] = rate(json.get(name)) ?: return null
        return rates
    }

    /** An array of names, or `null` when it is not an array or holds anything but strings. */
    private fun names(value: Any): Set<String>? {
        val json = value as? JSONArray ?: return null
        val names = HashSet<String>()
        for (i in 0 until json.length()) names += json.get(i) as? String ?: return null
        return names
    }
}
