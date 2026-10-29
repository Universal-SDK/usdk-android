package org.bidon.rendering.html

import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.annotation.RequiresApi
import org.bidon.rendering.telemetry.ProcessExit
import org.bidon.rendering.telemetry.RendererPriority

/**
 * How the render process described by [detail] ended. A missing [detail] says nothing about the cause or the
 * priority.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun processExitOf(detail: RenderProcessGoneDetail?): ProcessExit =
    processExitOf(detail?.didCrash(), detail?.rendererPriorityAtExit())

/**
 * How a render process ended, from the two raw values the platform reports: `didCrash()` and
 * `rendererPriorityAtExit()`. Split out of the function above so the mapping can be tested on the JVM, where a
 * `RenderProcessGoneDetail` cannot be built. A `null` crash flag stays unknown rather than counting as a system kill,
 * and a priority that is none of the three the platform defines is left out.
 */
internal fun processExitOf(didCrash: Boolean?, priority: Int?): ProcessExit {
    val priorityAtExit = when (priority) {
        WebView.RENDERER_PRIORITY_WAIVED -> RendererPriority.WAIVED
        WebView.RENDERER_PRIORITY_BOUND -> RendererPriority.BOUND
        WebView.RENDERER_PRIORITY_IMPORTANT -> RendererPriority.IMPORTANT
        else -> null
    }
    return ProcessExit(didCrash, priorityAtExit)
}
