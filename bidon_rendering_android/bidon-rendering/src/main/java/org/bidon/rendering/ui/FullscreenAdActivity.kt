package org.bidon.rendering.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.bidon.rendering.html.HtmlWebView

class FullscreenAdActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HTML = "extra_html"
        const val EXTRA_CLOSE_BUTTON_DELAY_MS = "extra_close_button_delay_ms"
    }

    private var webView: HtmlWebView? = null
    private var closeButton: CloseButton? = null

    private val showCloseBtnRunnable = Runnable {
        closeButton?.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        val root = FrameLayout(this)
        setContentView(root)

        val html = intent.getStringExtra(EXTRA_HTML) ?: run {
            finish()
            return
        }

        webView = HtmlWebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            loadDataWithBaseURL(
                null,
                html,
                "text/html",
                "UTF-8",
                null
            )
        }

        root.addView(webView)

        val margin = (16 * resources.displayMetrics.density).toInt()

        closeButton = CloseButton(this).apply {
            visibility = View.GONE

            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = margin
                topMargin = margin
            }

            onClick = { finish() }
        }

        root.addView(closeButton!!)

        val delayMs = intent.getLongExtra(EXTRA_CLOSE_BUTTON_DELAY_MS, 5000L)

        if (delayMs > 0) {
            closeButton?.postDelayed(showCloseBtnRunnable, delayMs)
        } else {
            closeButton?.visibility = View.VISIBLE
        }

        ViewCompat.setOnApplyWindowInsetsListener(closeButton!!) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            val baseMargin = (16 * resources.displayMetrics.density).toInt()

            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = safeArea.top + baseMargin
                rightMargin = safeArea.right + baseMargin
            }

            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        closeButton?.removeCallbacks(showCloseBtnRunnable)

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        webView = null
        closeButton = null
    }

    override fun onBackPressed() {
        finish()
    }
}