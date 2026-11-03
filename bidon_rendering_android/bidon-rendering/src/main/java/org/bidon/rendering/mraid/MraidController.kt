package org.bidon.rendering.mraid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import org.bidon.rendering.CloseButtonConfig
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.ui.CloseButton
import org.bidon.rendering.ui.insetFromSafeArea

/**
 * Carries out the creative's MRAID commands: state changes, resize, and expand into a fullscreen dialog.
 * Receives commands from MraidJsBridge (always called on main thread via bridge).
 */
internal class MraidController(
    private val context: Context,
    private val webView: MraidWebView,
    private val placement: MraidPlacementType,
    private val closeButton: CloseButtonConfig,
    private val onClose: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by [destroy]. Set and read on the main thread only: the bridge moves every command there first. */
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
    private var expanded: Expanded? = null

    /**
     * Set when the expanded ad goes back, whoever closed it, and cleared by the user's next touch on the banner or key
     * press in it. Without it a creative could expand again as soon as it hears `default`, after the user closed it or
     * after closing itself, and the user could never get the ad off the screen.
     */
    private var expandNeedsUserInput = false

    init {
        webView.onUserInput = { expandNeedsUserInput = false }
    }

    // ──────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────

    /** Call once the page has loaded. [afterReady] runs once the creative has heard `ready`, unless destroyed first. */
    fun onReady(afterReady: () -> Unit) {
        post {
            stateMachine.ready()
            webView.fireReadyEvent()
            webView.fireViewableChange(isViewable = true)
            afterReady()
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

    /**
     * Shows the banner over the whole screen of the activity it is on. The first expand needs no tap; each one after
     * needs the user to have touched the banner since the last. Otherwise the creative hears an error: a banner that
     * is not inline, not in `default`, waiting for the user, or not on screen.
     */
    fun expand() {
        post {
            if (!stateMachine.canTransitionTo(MraidState.EXPANDED)) {
                webView.fireError("Cannot expand here", "expand")
                return@post
            }
            if (expandNeedsUserInput) {
                webView.fireError("Expands again only after a touch on the ad", "expand")
                return@post
            }
            val activity = hostActivity() ?: run {
                webView.fireError("The ad is not on screen", "expand")
                return@post
            }
            expanded = Expanded(webView.parent as ViewGroup, activity)
            stateMachine.transitionTo(MraidState.EXPANDED)
        }
    }

    fun close() {
        post {
            when (currentState) {
                MraidState.EXPANDED -> expanded?.dismiss()
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

    // ──────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────

    /** The expanded banner's dialog was dismissed, whichever way. */
    private fun collapse() {
        val shown = expanded ?: return
        expanded = null
        // Before the creative hears `default`, which is where an expand loop would start.
        expandNeedsUserInput = true
        shown.putBack()
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

    /**
     * The running activity whose window the banner is shown in, found from the views above it. A view keeps the
     * activity it was made with, which need not be the one it is shown in (a container the host moved to the next
     * screen), so the window is checked: a dialog on any other activity would show nowhere, or fail to show at all.
     */
    private fun hostActivity(): Activity? {
        if (!webView.isShown) return null
        return generateSequence(webView.parent) { it.parent }
            .filterIsInstance<View>()
            .mapNotNull { it.context.findActivity() }
            // A destroyed activity keeps the token of the one that replaced it, so it can pass the window check.
            .firstOrNull { !it.isFinishing && !it.isDestroyed && it.showsWindowOf(webView) }
    }

    /**
     * The view is in this activity's own window, a popup on it, or a dialog it shows: a popup's window carries the
     * activity window's token, a dialog's carries the activity's own.
     */
    private fun Activity.showsWindowOf(view: View): Boolean {
        val decor = window.peekDecorView() ?: return false
        if (decor.applicationWindowToken === view.applicationWindowToken) return true
        val token = (view.rootView.layoutParams as? WindowManager.LayoutParams)?.token
        return token != null && token === window.attributes.token
    }

    fun destroy() {
        destroyed = true
        expanded?.discard()
        expanded = null
    }

    /**
     * The expanded banner, in a fullscreen dialog over the host's screen. A dialog is a window of its own: Back reaches
     * it whatever the host activity is, and the host is not paused. The close button, Back, `mraid.close()` and the
     * host's screen going away all dismiss the dialog, and the dismiss puts the banner back.
     */
    private inner class Expanded(private val container: ViewGroup, activity: Activity) {
        private val index = container.indexOfChild(webView)
        private val layoutParams = webView.layoutParams
        private val dialog = ComponentDialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // The host's screen is going away, rotation included. The dialog closes now, before Android closes it as a
        // leaked window; the banner goes back once the dismiss is delivered, as a view must not be added to a
        // container in the middle of leaving its window.
        private val containerGone = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = dialog.dismiss()
        }

        init {
            container.removeView(webView)
            val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            root.addView(
                webView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            val close = CloseButton(activity, closeButton).apply { onClick = { dialog.dismiss() } }
            root.addView(close, close.cornerLayoutParams())
            close.insetFromSafeArea(marginDp = 0)
            // The ad is on screen at the creative's call, so the user may close it at once: there is no close delay
            // as for a fullscreen ad.
            close.becomeAvailableAt(availableAtMs = 0L) {}

            dialog.setContentView(root)
            dialog.window?.let(AdWindow::goFullscreen)
            dialog.setOnDismissListener { collapse() }
            container.addOnAttachStateChangeListener(containerGone)
            dialog.show()
        }

        fun dismiss() = dialog.dismiss()

        /** Back where the host placed it, at the same size and position among the container's views. */
        fun putBack() {
            container.removeOnAttachStateChangeListener(containerGone)
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.addView(webView, index.coerceAtMost(container.childCount), layoutParams)
        }

        /** For a banner being destroyed: the dialog goes, and nothing is put back. */
        fun discard() {
            container.removeOnAttachStateChangeListener(containerGone)
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
