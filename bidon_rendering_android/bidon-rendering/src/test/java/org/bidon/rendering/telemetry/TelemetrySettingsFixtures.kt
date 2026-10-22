package org.bidon.rendering.telemetry

/** The values a [TelemetryEndpoint] carries, so a failed comparison prints which one differs. */
internal data class EndpointView(val url: String, val appKey: String, val bundle: String, val sdkVersion: String)

internal data class SettingsView(val endpoint: EndpointView?, val config: TelemetryConfig?)

internal fun TelemetryEndpoint.view() = EndpointView(url.toString(), appKey, bundle, sdkVersion)

internal fun TelemetrySettings.view() = SettingsView(endpoint?.view(), config)

internal const val TEST_BUNDLE = "com.example.app"
internal const val TEST_SDK_VERSION = "1.2.3"
internal const val TEST_URL = "https://ingest.example.com/v2/telemetry"

internal fun testEndpoint(
    url: String = TEST_URL,
    appKey: String = "app-key",
    bundle: String = TEST_BUNDLE,
    sdkVersion: String = TEST_SDK_VERSION,
): TelemetryEndpoint = checkNotNull(TelemetryEndpoint.of(url, appKey, bundle, sdkVersion)) { "test endpoint refused" }

internal val FULL_CONFIG = TelemetryConfig(
    enabled = false,
    samplingRate = 0.25,
    eventRates = mapOf("ad_loaded" to 0.05, "ad_shown" to 0.0),
    allowedEvents = setOf("ad_loaded", "ad_shown"),
)

internal fun fullSettings(url: String = TEST_URL, appKey: String = "app-key") =
    TelemetrySettings(testEndpoint(url, appKey), FULL_CONFIG)
