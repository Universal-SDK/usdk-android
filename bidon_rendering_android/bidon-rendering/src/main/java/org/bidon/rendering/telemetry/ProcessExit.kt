package org.bidon.rendering.telemetry

/**
 * How the WebView's render process ended, as far as the platform said. A field is `null` when the platform gave no
 * detail or a value this version does not know, and is then left out of the event rather than guessed.
 *
 * @param didCrash `true` when the process crashed, `false` when the system killed it
 * @param priorityAtExit how important the system considered the process when it ended
 */
internal data class ProcessExit(val didCrash: Boolean?, val priorityAtExit: RendererPriority?)

/** How important the system considered a WebView render process, as it is written on the wire. */
internal enum class RendererPriority(val wireName: String) {
    WAIVED("waived"),
    BOUND("bound"),
    IMPORTANT("important"),
}
