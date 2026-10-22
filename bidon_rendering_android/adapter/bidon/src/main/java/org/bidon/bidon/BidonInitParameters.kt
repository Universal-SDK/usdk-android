package org.bidon.bidon

import org.bidon.rendering.LogLevel
import org.bidon.rendering.RendererConfig
import org.bidon.sdk.adapter.AdapterParameters
import org.json.JSONObject

data class BidonInitParameters(
    val sdkInstanceId: String? = null,
    /** The `telemetry` block of this adapter's config section, handed to the renderer as is. */
    val telemetry: JSONObject? = null,
    val debug: Boolean = false,
    val timeoutMs: Long = 5000L
) : AdapterParameters {

    companion object {
        fun default() = BidonInitParameters()
    }

    fun toRendererConfig(): RendererConfig = RendererConfig(
        logLevel = if (debug) LogLevel.DEBUG else LogLevel.INFO,
        networkTimeoutMs = timeoutMs,
        enableDebugging = debug
    )
}