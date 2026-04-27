package org.bidon.bidon

import org.bidon.sdk.adapter.AdapterParameters
internal data class BidonInitParameters(
    val endpoint: String? = null,
    val apiKey: String? = null,
    val debug: Boolean = false,
    val timeoutMs: Long = 5000L
) : AdapterParameters {

    companion object {
        fun default() = BidonInitParameters()
    }
}