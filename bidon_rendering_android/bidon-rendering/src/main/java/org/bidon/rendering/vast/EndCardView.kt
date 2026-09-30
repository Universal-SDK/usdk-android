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
import org.bidon.rendering.vast.CompanionResource
import org.bidon.rendering.vast.VastCompanion
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.ui.clickOnlyOnTap
import org.bidon.rendering.ui.dp
import org.bidon.rendering.ui.insetFromSafeArea

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

            layoutParams = LayoutParams(
                dp(AdWindow.TOUCH_TARGET_DP),
                dp(AdWindow.TOUCH_TARGET_DP)
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }

            setOnClickListener {
                listener?.onClose()
            }
        }

        ctaButton = TextView(context).apply {
            text = "Learn More"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(0xFF1976D2.toInt())

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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

    fun showCompanion(companion: VastCompanion) {

        removeAdContent()

        clickThroughUrl = companion.clickThroughUrl

        val contentView = when (val resource = companion.resource) {

            is CompanionResource.StaticImage -> {
                // A StaticResource is only an image when creativeType says so. HTML and
                // JavaScript creative types have to go through the WebView instead.
                if (isImageCreativeType(resource.creativeType)) {
                    createImage(resource.url)
                } else {
                    createIframe(resource.url)
                }
            }

            is CompanionResource.Html -> {
                createHtml(resource.html)
            }

            is CompanionResource.IFrame -> {
                createIframe(resource.url)
            }
        }

        addView(contentView)

        addView(closeButton)

        ctaButton.visibility =
            if (clickThroughUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        addView(ctaButton)

        // The companion image fills the screen behind the notch and the bars; the close button
        // and the call to action must not. This view was missed when the other ad surfaces were
        // done, so its close button sat in the status bar strip.
        closeButton.insetFromSafeArea(AdWindow.CONTROL_MARGIN_DP)
        ctaButton.insetFromSafeArea(AdWindow.CONTROL_MARGIN_DP)
    }

    private fun isImageCreativeType(creativeType: String?): Boolean {
        // No creativeType at all is the common case in the wild, and it means an image.
        if (creativeType.isNullOrBlank()) return true
        return creativeType.startsWith("image/", ignoreCase = true)
    }

    private fun createImage(url: String): View {

        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )

            setOnClickListener {
                // A tap that opens nothing is not a click, as on the video.
                if (clickThroughUrl.isNullOrBlank()) return@setOnClickListener
                listener?.onClick()
                openClickThrough()
            }
            clickOnlyOnTap()

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
                onPageLoadError = { listener?.onError(Exception(it)) },
                onWebViewGone = {
                    // Already destroyed by the ClickHandler. The error ends the video ad's screen.
                    webView = null
                    listener?.onError(IllegalStateException("The end card's renderer process is gone"))
                }
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