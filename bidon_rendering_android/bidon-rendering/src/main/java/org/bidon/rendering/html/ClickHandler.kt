package org.bidon.rendering.html

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class ClickHandler(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onPageLoaded: (() -> Unit)? = null,
    private val onPageLoadError: ((String) -> Unit)? = null
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {

        val uri = request?.url ?: return false
        val url = uri.toString()

        if (url.startsWith("javascript:") ||
            url.startsWith("about:blank")
        ) {
            return false
        }

        Log.d("ClickHandler", "Intercepted click: $url")

        onClick()

        return handleUrl(uri)
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false

        return try {
            when (scheme) {

                "http", "https" -> {
                    openExternal(uri)
                    true
                }

                "market" -> {
                    openExternal(uri)
                    true
                }

                "intent" -> {
                    handleIntentScheme(uri.toString())
                    true
                }

                else -> {
                    openExternal(uri)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("ClickHandler", "Failed to handle url: $uri", e)
            false
        }
    }

    private fun handleIntentScheme(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

            val fallbackUrl = intent.getStringExtra("browser_fallback_url")

            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {

                if (!fallbackUrl.isNullOrEmpty()) {
                    openExternal(Uri.parse(fallbackUrl))
                } else {
                    Log.w("ClickHandler", "No app and no fallback for intent")
                }
            }

        } catch (e: Exception) {
            Log.e("ClickHandler", "Invalid intent url: $url", e)
        }
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d("ClickHandler", "Page loaded: $url")
        onPageLoaded?.invoke()
    }

    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)

        val errorMsg = description ?: "Unknown error ($errorCode)"
        Log.e("ClickHandler", "Load error: $errorMsg")

        onPageLoadError?.invoke(errorMsg)
    }
}