package org.bidon.rendering

import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.ErrorCode

/**
 * Ends a show request with [code]: [events] get the code, and [listener] the [cause], or an error naming the code when
 * there is no exception behind it.
 */
internal fun failShow(
    events: AdEvents?,
    listener: AdRenderer.Listener?,
    code: ErrorCode,
    externalCode: String? = null,
    cause: Throwable? = null,
) {
    events?.showFailed(code, externalCode, cause)
    listener?.onError(cause ?: IllegalStateException("The ad cannot be shown: ${code.name} (${code.number})"))
}
