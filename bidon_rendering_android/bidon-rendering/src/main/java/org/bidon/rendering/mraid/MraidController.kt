package org.bidon.rendering.mraid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.bidon.rendering.ui.FullscreenAdActivity

/**
 * Central MRAID coordinator.
 * Receives commands from MraidJsBridge (always called on main thread via bridge).
 * Manages view transitions between inline and expanded/fullscreen state.
 */
class MraidController(
    private val context: Context,
    private val webView: MraidWebView,
    private val placement: MraidPlacementType,
    private val onClose: () -> Unit,
    private val onClick: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set on the main thread by [destroy]; read by bridge calls, which arrive on another thread. */
    @Volatile
    private var destroyed = false

    /**
     * Runs [block] on the main thread unless the controller has been destroyed by then. Commands from the creative
     * can still be on their way when its WebView is destroyed, the renderer process dying included, and must not
     * reach the destroyed WebView or report anything for an ad that has ended.
     */
    private fun post(block: () -> Unit) {
        mainHandler.post { if (!destroyed) block() }
    }

    private val stateMachine = MraidStateMachine(placement) { newState ->
        webView.fireStateChange(newState)
    }

    /** Inline while the creative sits in a container, interstitial once it goes fullscreen. */
    fun setPlacement(placement: MraidPlacementType) {
        stateMachine.placement = placement
    }

    val currentState: MraidState get() = stateMachine.current
    private var originalContainer: ViewGroup? = null
    private var expandOverlay: FrameLayout? = null

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    /** Call after WebView is attached and mraid.js is injected */
    fun onReady() {
        post {
            stateMachine.transitionTo(MraidState.DEFAULT)
            webView.fireReadyEvent()
            webView.fireViewableChange(isViewable = true)
        }
    }

    fun onViewabilityChanged(isViewable: Boolean) {
        post {
            webView.fireViewableChange(isViewable)
        }
    }

    // ──────────────────────────────────────────
    // MRAID commands
    // ──────────────────────────────────────────

    fun open(url: String) {
        post {
            onClick()
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
            }
        }
    }

    fun expand(url: String?) {
        if (currentState != MraidState.DEFAULT) return

        post {
            val activity = findActivity() ?: return@post

            originalContainer = webView.parent as? ViewGroup
            originalContainer?.removeView(webView)

            val overlay = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            webView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            overlay.addView(webView)

            val root = activity.window.decorView.rootView as? ViewGroup
            root?.addView(overlay)

            expandOverlay = overlay

            if (!url.isNullOrBlank()) {
                webView.loadUrl(url)
            }

            stateMachine.transitionTo(MraidState.EXPANDED)
        }
    }

    fun close() {
        post {
            when (currentState) {
                MraidState.EXPANDED -> collapseFromExpanded()
                MraidState.RESIZED -> collapseFromResized()
                MraidState.DEFAULT -> {
                    stateMachine.transitionTo(MraidState.HIDDEN)
                    onClose()
                }
                else -> {}
            }
        }
    }

    fun resize(width: Int, height: Int, offsetX: Int, offsetY: Int) {
        if (currentState != MraidState.DEFAULT && currentState != MraidState.RESIZED) return

        post {
            val density = context.resources.displayMetrics.density

            val widthPx = (width * density).toInt()
            val heightPx = (height * density).toInt()
            val offsetXPx = (offsetX * density).toInt()
            val offsetYPx = (offsetY * density).toInt()

            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels

            val clampedWidth = widthPx.coerceAtMost(screenWidth)
            val clampedHeight = heightPx.coerceAtMost(screenHeight)

            val params = webView.layoutParams
            params?.width = clampedWidth
            params?.height = clampedHeight
            webView.layoutParams = params

            webView.translationX = offsetXPx.toFloat()
            webView.translationY = offsetYPx.toFloat()

            stateMachine.transitionTo(MraidState.RESIZED)
        }
    }

    fun playVideo(url: String) {
        post {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {

            }
        }
    }

    fun storePicture(url: String) {
        post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun createCalendarEvent(description: String, start: String) {
        post {
            try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, description)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────

    private fun collapseFromExpanded() {
        val overlay = expandOverlay ?: return

        overlay.removeView(webView)

        val root = findActivity()?.window?.decorView?.rootView as? ViewGroup
        root?.removeView(overlay)

        expandOverlay = null

        originalContainer?.addView(webView)
        originalContainer = null

        stateMachine.transitionTo(MraidState.DEFAULT)
    }

    private fun collapseFromResized() {
        // Reset size and translation
        webView.translationX = 0f
        webView.translationY = 0f

        val params = webView.layoutParams
        params?.width = ViewGroup.LayoutParams.MATCH_PARENT
        params?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        webView.layoutParams = params

        stateMachine.transitionTo(MraidState.DEFAULT)
    }

    private fun findActivity(): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        expandOverlay?.let { overlay ->
            overlay.removeView(webView)
            val root = findActivity()?.window?.decorView?.rootView as? ViewGroup
            root?.removeView(overlay)
        }
        expandOverlay = null
        originalContainer = null
    }
}