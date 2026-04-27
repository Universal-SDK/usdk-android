/*package org.bidon.rendering.html

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.rendering.ui.FullscreenAdActivity

class HtmlRenderer(private val context: Context) : AdRenderer {

    private var webView: HtmlWebView? = null
    private var lastLoadedHtml: String? = null

    override fun load(
        adm: String,
        nurl: String,
        onLoaded: () -> Unit,
        onClick: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        TrackingDispatcher.fire(nurl)

        val processedHtml = injectViewportMetaTag(adm)
        lastLoadedHtml = processedHtml

        webView = HtmlWebView(context).apply {

            webViewClient = ClickHandler(
                context = context,
                onClick = onClick,
                onPageLoaded = { onLoaded() },
                onPageLoadError = { errorMsg -> onError(Exception(errorMsg)) }
            )

            webChromeClient = WebChromeClient()

            try {
                loadHtmlContent(processedHtml)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun injectViewportMetaTag(html: String): String {
        if (html.contains("viewport", ignoreCase = true)) return html

        val viewportTag = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">"

        return when {
            html.contains("<head>", ignoreCase = true) ->
                html.replace("<head>", "<head>$viewportTag", ignoreCase = true)
            html.contains("<html>", ignoreCase = true) ->
                html.replace("<html>", "<html><head>$viewportTag</head>", ignoreCase = true)
            else -> "$viewportTag$html"
        }
    }

    override fun show(
        container: ViewGroup?,
        burl: String,
        isFullscreen: Boolean,
        closeButtonDelayMs: Long
    ) {
        if (isFullscreen) {
            showFullscreen(burl, closeButtonDelayMs)
        } else {
            showInline(container ?: return, burl)
        }
    }

    private fun showInline(container: ViewGroup, burl: String) {
        TrackingDispatcher.fire(burl)
        webView?.let {
            container.removeAllViews()
            container.addView(it)
        }
    }

    private fun showFullscreen(burl: String, delayMs: Long) {
        TrackingDispatcher.fire(burl)
        val htmlContent = lastLoadedHtml ?: ""

        val intent = Intent(context, FullscreenAdActivity::class.java).apply {
            putExtra(FullscreenAdActivity.EXTRA_HTML_CONTENT, htmlContent)
            putExtra(FullscreenAdActivity.EXTRA_CLOSE_BUTTON_DELAY_MS, delayMs)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun getView(): View? = webView

    override fun destroy() {
        webView?.destroy()
        webView = null
        lastLoadedHtml = null
    }
}*/



/*package org.bidon.rendering.html

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.ui.FullscreenAdActivity
import org.bidon.rendering.ui.FullscreenCache

class HtmlRenderer(context: Context) : AdRenderer {

    private val context = context.applicationContext

    private var webView: HtmlWebView? = null
    private var listener: AdRenderer.Listener? = null
    private var isLoaded = false

    override fun load(
        adm: String,
        listener: AdRenderer.Listener
    ) {
        this.listener = listener

        val processedHtml = injectViewportMetaTag(adm)

        webView = HtmlWebView(context).apply {

            webViewClient = ClickHandler(
                context = context,
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
                    processedHtml,
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

    override fun showFullscreen(activity: Activity) {
        val view = webView ?: run {
            listener?.onError(IllegalStateException("Ad not loaded"))
            return
        }

        FullscreenCache.webView = view

        val intent = Intent(activity, FullscreenAdActivity::class.java)
        activity.startActivity(intent)

        notifyImpression()
    }

    fun attachToContainer(container: ViewGroup) {
        val view = webView ?: return

        if (view.parent != null) {
            (view.parent as ViewGroup).removeView(view)
        }

        container.addView(view)

        notifyImpression()
    }

    private fun notifyImpression() {
        webView?.post {
            listener?.onImpression()
        }
    }

    override fun destroy() {
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
}*/
























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