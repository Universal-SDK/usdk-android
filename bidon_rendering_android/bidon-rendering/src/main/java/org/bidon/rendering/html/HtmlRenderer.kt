package org.bidon.rendering.html

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.ui.FullscreenAdActivity
import android.app.Activity
import android.content.Intent

class HtmlRenderer(
    context: Context
) : AdRenderer {

    private val appContext = context.applicationContext

    private var webView: HtmlWebView? = null
    private var listener: AdRenderer.Listener? = null

    private var isLoaded = false
    private var isDestroyed = false

    private var lastHtml: String? = null

    override fun load(
        adm: String,
        listener: AdRenderer.Listener
    ) {
        this.listener = listener
        isDestroyed = false

        val html = injectViewportMetaTag(adm)
        lastHtml = html

        webView = HtmlWebView(appContext).apply {

            webViewClient = ClickHandler(
                context = appContext,
                onClick = {
                    this@HtmlRenderer.listener?.onClick()
                },
                onPageLoaded = {
                    isLoaded = true
                    this@HtmlRenderer.listener?.onLoaded()
                },
                onPageLoadError = { error ->
                    this@HtmlRenderer.listener?.onError(Exception(error))
                }
            )

            webChromeClient = WebChromeClient()

            try {
                loadDataWithBaseURL(
                    null,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            } catch (e: Exception) {
                listener.onError(e)
            }
        }
    }

    override fun getView(): View? = webView

    override fun attach(container: ViewGroup) {
        val view = webView ?: return
        if (isDestroyed) return

        if (view.parent != null) {
            (view.parent as ViewGroup).removeView(view)
        }

        container.addView(view)
        listener?.onImpression()
    }

    override fun showFullscreen(activity: Activity) {
        val html = lastHtml ?: run {
            listener?.onError(IllegalStateException("Ad not loaded"))
            return
        }

        val intent = Intent(activity, FullscreenAdActivity::class.java).apply {
            putExtra(FullscreenAdActivity.EXTRA_HTML, html)
            putExtra(FullscreenAdActivity.EXTRA_CLOSE_BUTTON_DELAY_MS, 3000L)
        }

        activity.startActivity(intent)

        listener?.onImpression()
    }

    override fun destroy() {
        isDestroyed = true

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        webView = null
        listener = null
    }

    private fun injectViewportMetaTag(html: String): String {
        if (html.contains("viewport", ignoreCase = true)) return html

        val tag =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">"

        return when {
            html.contains("<head>", true) ->
                html.replace("<head>", "<head>$tag", true)

            html.contains("<html>", true) ->
                html.replace("<html>", "<html><head>$tag</head>", true)

            else -> "$tag$html"
        }
    }
}