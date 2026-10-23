package org.bidon.rendering.vast

import org.bidon.rendering.telemetry.ErrorCode

/** How a failed VAST load is reported: the code, and the VAST or HTTP code behind it. */
internal data class VastLoadFailure(val code: ErrorCode, val externalCode: String?) {

    companion object {

        /** The status `HttpURLConnection` gives for an answer that is not valid HTTP. */
        private const val NOT_HTTP = -1

        /** The failure [error] stands for; anything that is not a parsing failure is an invalid document. */
        fun classify(error: Throwable): VastLoadFailure =
            when ((error as? VastParsingException)?.vastError?.code) {
                300 -> VastLoadFailure(ErrorCode.VAST_WRAPPER_NO_URI, "300")
                301 -> classifyFailedFetch(error.cause)
                302 -> VastLoadFailure(ErrorCode.VAST_WRAPPER_TOO_DEEP, "302")
                303 -> VastLoadFailure(ErrorCode.VAST_WRAPPED_NO_AD, "303")
                else -> VastLoadFailure(ErrorCode.VAST_INVALID, "100")
            }

        private fun classifyFailedFetch(cause: Throwable?): VastLoadFailure = when {
            cause is VastWrapperHttpException && cause.status != NOT_HTTP ->
                VastLoadFailure(ErrorCode.VAST_WRAPPER_HTTP_STATUS, cause.status.toString())
            cause is VastWrapperCleartextException -> VastLoadFailure(ErrorCode.VAST_WRAPPER_CLEARTEXT_BLOCKED, "301")
            else -> VastLoadFailure(ErrorCode.VAST_WRAPPER_NO_ANSWER, "301")
        }
    }
}
