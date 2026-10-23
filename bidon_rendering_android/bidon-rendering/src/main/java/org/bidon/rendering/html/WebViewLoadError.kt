package org.bidon.rendering.html

import android.webkit.WebViewClient
import org.bidon.rendering.telemetry.ErrorCode

/**
 * The code for a main frame that failed before the page loaded, from the `WebViewClient.ERROR_*` value the WebView
 * reported: a network error is the network's failure, anything else the creative's.
 */
internal fun ErrorCode.Companion.forWebViewError(errorCode: Int): ErrorCode = when (errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP,
    WebViewClient.ERROR_CONNECT,
    WebViewClient.ERROR_IO,
    WebViewClient.ERROR_TIMEOUT -> ErrorCode.WEBVIEW_MAIN_FRAME_NETWORK
    else -> ErrorCode.WEBVIEW_MAIN_FRAME_ERROR
}
