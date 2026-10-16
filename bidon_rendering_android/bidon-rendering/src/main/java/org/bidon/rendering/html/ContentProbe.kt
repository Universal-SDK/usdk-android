package org.bidon.rendering.html

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView

/**
 * Tells whether a loaded page shows anything that can be seen, waiting up to [EMPTY_PAGE_WAIT_MILLIS] for a page that is empty
 * when it finishes loading. Reads the page, never its size: the WebView is not in a window while the ad loads.
 *
 * [onResult] is called once, on the main thread, unless [cancel] is called first. Call from the main thread.
 */
internal class ContentProbe(
    private val webView: WebView,
    private val onResult: (hasContent: Boolean) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var isDone = false

    fun start() {
        deadline = SystemClock.uptimeMillis() + EMPTY_PAGE_WAIT_MILLIS
        // A page whose script holds its thread never answers; it is not judged, so it counts as content.
        handler.postDelayed({ finish(hasContent = true) }, EMPTY_PAGE_WAIT_MILLIS + ANSWER_GRACE_MILLIS)
        check()
    }

    fun cancel() {
        isDone = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun check() {
        webView.evaluateJavascript(HAS_CONTENT) { answer ->
            if (isDone) return@evaluateJavascript
            when {
                // Only a definite "no" counts: a page the script could not read is given the benefit of the doubt.
                answer != "false" -> finish(hasContent = true)
                SystemClock.uptimeMillis() >= deadline -> finish(hasContent = false)
                else -> handler.postDelayed(::check, POLL_MILLIS)
            }
        }
    }

    private fun finish(hasContent: Boolean) {
        if (isDone) return
        cancel()
        onResult(hasContent)
    }

    companion object {
        /** How long an empty page has to show something before it counts as blank. */
        const val EMPTY_PAGE_WAIT_MILLIS = 2_000L

        /** What a renderer reports for a page that showed nothing. */
        fun blankCreative() = IllegalStateException("The creative shows nothing")

        private const val POLL_MILLIS = 100L

        /** How much longer than the wait a page that does not answer at all is given. */
        private const val ANSWER_GRACE_MILLIS = 500L

        /**
         * True when the page has text, a picture larger than a tracking pixel, a canvas, video or embedded frame, or
         * anything with a background, that is not hidden. Anything it cannot read counts as content: a shadow root,
         * a custom element, generated `::before` or `::after` content, a picture still on its way, and an MRAID
         * creative that waits to be on screen before it draws. So an MRAID creative that asks whether it is on screen
         * before it is, as a viewability tracker does, is not judged at all.
         */
        private val HAS_CONTENT = """
            (function () {
              try {
                if (window.mraid && typeof mraid._waitsForViewable === 'function' && mraid._waitsForViewable()) return true;
                // Visibility is inherited and a child can turn it back on, so only the element's own counts.
                function shown(node) {
                  if (getComputedStyle(node).visibility === 'hidden') return false;
                  for (var e = node; e && e.nodeType === 1; e = e.parentElement) {
                    var s = getComputedStyle(e);
                    if (s.display === 'none' || s.opacity === '0') return false;
                  }
                  return true;
                }
                // A loaded picture counts by its own size, so a failed one does not. One still on its way counts, unless
                // its markup sizes it as a tracking pixel.
                function picture(img) {
                  if (img.complete) return img.naturalWidth > 1 || img.naturalHeight > 1;
                  return !(pixelSize(img.getAttribute('width')) && pixelSize(img.getAttribute('height')));
                }
                function pixelSize(value) {
                  return value !== null && value.trim() !== '' && +value <= 1;
                }
                function generated(e, which) {
                  var c = getComputedStyle(e, which).content;
                  return c && c !== 'none' && c !== 'normal';
                }
                function painted(colour) {
                  return colour !== 'transparent' && !/^rgba\(.*,\s*0\)$/.test(colour);
                }
                var texts = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_TEXT);
                for (var t = texts.nextNode(); t; t = texts.nextNode()) {
                  // A script or style is display:none. What a noscript holds is never shown while scripts run, but it is
                  // not hidden by its style.
                  var parent = t.parentElement;
                  if (parent && parent.tagName !== 'NOSCRIPT' && t.nodeValue.trim() && shown(parent)) return true;
                }
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                  var e = all[i];
                  var tag = e.tagName;
                  var content =
                    e.shadowRoot || tag.indexOf('-') >= 0 ||
                    generated(e, '::before') || generated(e, '::after') ||
                    /^(CANVAS|VIDEO|SVG|IFRAME|EMBED|OBJECT)$/i.test(tag) ||
                    (tag === 'IMG' && picture(e)) ||
                    getComputedStyle(e).backgroundImage !== 'none' ||
                    painted(getComputedStyle(e).backgroundColor);
                  if (content && shown(e)) return true;
                }
                return false;
              } catch (error) {
                return true;
              }
            })()
        """.trimIndent()
    }
}
