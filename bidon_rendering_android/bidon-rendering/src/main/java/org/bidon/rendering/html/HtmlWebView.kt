package org.bidon.rendering.html

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView

class HtmlWebView(context: Context) : WebView(context) {

    init {
        setupWebView()
    }
    private fun setupWebView() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0)
    }

    fun loadHtmlContent(html: String, baseUrl: String? = null) {
        loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    fun cleanup() {
        stopLoading()
    }
}