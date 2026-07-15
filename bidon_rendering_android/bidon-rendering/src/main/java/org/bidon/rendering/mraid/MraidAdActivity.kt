package org.bidon.rendering.mraid.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import org.bidon.rendering.mraid.MraidController
import org.bidon.rendering.mraid.MraidJsBridge
import org.bidon.rendering.mraid.MraidPlacementType
import org.bidon.rendering.mraid.MraidWebView
import org.bidon.rendering.ui.CloseButton
import android.util.Log

class MraidAdActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MraidAdActivity"
        private const val EXTRA_HTML = "extra_html"
        private const val EXTRA_MRAID_JS = "extra_mraid_js"
        private const val EXTRA_CLOSE_BUTTON_DELAY_MS = "extra_close_button_delay_ms"

        fun start(
            context: android.content.Context,
            html: String,
            mraidJs: String,
            closeButtonDelayMs: Long = 5000L
        ) {
            val intent = android.content.Intent(context, MraidAdActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_HTML, html)
                putExtra(EXTRA_MRAID_JS, mraidJs)
                putExtra(EXTRA_CLOSE_BUTTON_DELAY_MS, closeButtonDelayMs)
            }
            context.startActivity(intent)
        }
    }

    private var webView: MraidWebView? = null
    private var controller: MraidController? = null
    private var closeButton: CloseButton? = null
    private var loadingProgressBar: ProgressBar? = null
    private var root: FrameLayout? = null

    private val showCloseBtnRunnable = Runnable {
        Log.d(TAG, "showCloseBtnRunnable")
        closeButton?.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")

        supportActionBar?.hide()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        window.setBackgroundDrawableResource(android.R.color.transparent)

        val html = intent.getStringExtra(EXTRA_HTML) ?: run {
            Log.e(TAG, "ERROR: HTML")
            finish()
            return
        }
        val mraidJs = intent.getStringExtra(EXTRA_MRAID_JS) ?: run {
            Log.e(TAG, "ERROR: MRAID JS")
            finish()
            return
        }

        val wv = MraidWebView(this)
        webView = wv

        // 2. Створюємо Controller
        val ctrl = MraidController(
            context = this,
            webView = wv,
            placement = MraidPlacementType.INTERSTITIAL,
            onClose = {
                Log.d(TAG, "onClose callback")
                finish()
            },
            onClick = {
                Log.d(TAG, "onClick callback")
            }
        )
        controller = ctrl
        val bridge = MraidJsBridge(ctrl)

        wv.attachBridge(
            bridge = bridge,
            onClick = {
                Log.d(TAG, "WebView onClick")
            },
            onPageLoaded = {
                Log.d(TAG, "✅ onPageLoaded")
                loadingProgressBar?.visibility = View.GONE
                ctrl.onReady()
            },
            onPageLoadError = {
                Log.e(TAG, "❌ onPageLoadError")
                loadingProgressBar?.visibility = View.GONE
            }
        )

        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(Color.TRANSPARENT)

        // 6. Створюємо Root FrameLayout
        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        root!!.addView(wv)

        loadingProgressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            isIndeterminate = true
        }
        root!!.addView(loadingProgressBar!!)

        closeButton = CloseButton(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(70, 70).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (16 * resources.displayMetrics.density).toInt()
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            }
            onClick = {
                Log.d(TAG, "Close button clicked")
                finish()
            }
        }
        root!!.addView(closeButton!!)
        val delayMs = intent.getLongExtra(EXTRA_CLOSE_BUTTON_DELAY_MS, 5000L)
        if (delayMs > 0) {
            closeButton?.postDelayed(showCloseBtnRunnable, delayMs)
        } else {
            closeButton?.visibility = View.VISIBLE
        }

        setContentView(root)
        wv.loadMraidContent(html, mraidJs)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")

        closeButton?.removeCallbacks(showCloseBtnRunnable)

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        controller?.destroy()
        controller = null
        closeButton = null
        loadingProgressBar = null
        root = null
    }

    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed()")
        finish()
    }
}