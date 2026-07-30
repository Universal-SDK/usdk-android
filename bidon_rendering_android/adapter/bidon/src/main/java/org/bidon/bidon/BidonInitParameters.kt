package org.bidon.bidon

import org.bidon.rendering.LogLevel
import org.bidon.rendering.RendererConfig
import org.bidon.sdk.adapter.AdapterParameters

data class BidonInitParameters(
    val sdkInstanceId: String? = null,
    val endpoint: String? = null,
    val apiKey: String? = null,
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