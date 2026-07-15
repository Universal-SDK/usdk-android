package org.bidon.rendering.vast.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import coil.load
import org.bidon.rendering.html.ClickHandler
import org.bidon.rendering.html.HtmlWebView
import org.bidon.rendering.vast.VastCompanion

class EndCardView(
    context: Context
) : FrameLayout(context) {

    interface Listener {
        fun onClose()
        fun onClick()
        fun onError(error: Throwable)
    }

    private var listener: Listener? = null

    private var imageView: ImageView? = null
    private var webView: HtmlWebView? = null

    private val closeButton: ImageButton
    private val ctaButton: TextView

    private var clickThroughUrl: String? = null

    init {
        setBackgroundColor(Color.BLACK)

        closeButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x66000000)

            layoutParams = LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 40
                marginEnd = 40
            }

            setOnClickListener {
                listener?.onClose()
            }
        }

        ctaButton = TextView(context).apply {
            text = "Learn More"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xFF1976D2.toInt())

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 48
            }

            setOnClickListener {
                listener?.onClick()
                openClickThrough()
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun showCompanion(companion: org.bidon.rendering.vast.VastCompanion) {

        removeAdContent()

        clickThroughUrl = companion.clickThroughUrl

        val contentView = when (val resource = companion.resource) {

            is org.bidon.rendering.vast.CompanionResource.StaticImage -> {
                createImage(resource.url)
            }

            is org.bidon.rendering.vast.CompanionResource.Html -> {
                createHtml(resource.html)
            }

            is org.bidon.rendering.vast.CompanionResource.IFrame -> {
                createIframe(resource.url)
            }

            null -> {
                listener?.onError(IllegalStateException("No companion resource"))
                return
            }
        }

        addView(contentView)

        addView(closeButton)
        addView(ctaButton)
    }

    private fun createImage(url: String): View {

        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )

            setOnClickListener {
                listener?.onClick()
                openClickThrough()
            }

            load(url) {
                crossfade(true)
                listener(
                    onError = { _, throwable ->
                        listener?.onError(
                            throwable.throwable ?: Exception("Image load error")
                        )
                    }
                )
            }

            imageView = this
        }
    }

    private fun createHtml(html: String): View {

        return HtmlWebView(context).apply {

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )

            webViewClient = ClickHandler(
                context = context,
                onClick = { listener?.onClick() },
                onPageLoaded = {},
                onPageLoadError = { listener?.onError(Exception(it)) }
            )

            webChromeClient = WebChromeClient()

            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)

            webView = this
        }
    }

    private fun createIframe(url: String): View {

        val html = """
            <html>
            <body style="margin:0;padding:0;overflow:hidden;">
                <iframe src="$url"
                        width="100%"
                        height="100%"
                        style="border:none;" />
            </body>
            </html>
        """.trimIndent()

        return createHtml(html)
    }

    private fun removeAdContent() {

        imageView?.let { removeView(it) }
        webView?.let { removeView(it) }

        removeView(closeButton)
        removeView(ctaButton)

        imageView = null
        webView = null
    }

    fun destroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }

        webView = null
        imageView = null
        listener = null
    }

    private fun openClickThrough() {
        clickThroughUrl ?: return

        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(clickThroughUrl))
            )
        } catch (_: Exception) {}
    }
}