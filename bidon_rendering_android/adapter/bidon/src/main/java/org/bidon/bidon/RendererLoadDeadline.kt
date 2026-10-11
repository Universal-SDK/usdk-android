package org.bidon.bidon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bidon.rendering.AdRenderer
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a creative's load to the ad unit's timeout, and passes the renderer's events on to [listener]. The timeout
 * counts from construction, so it is built right before the load starts.
 *
 * The first of loaded, failed and the timeout settles the load. When the timeout comes first, [onTimeout] runs once
 * and the renderer is given up: nothing it reports afterwards is passed on, so a load that completes late reports
 * neither a fill nor a second failure. Otherwise every event passes on, an error after the load included, so an ad
 * that fails while it shows still says so.
 */
internal class RendererLoadDeadline(
    scope: CoroutineScope,
    timeoutMs: Long,
    private val listener: AdRenderer.Listener,
    private val onTimeout: () -> Unit,
) : AdRenderer.Listener {

    private enum class State { LOADING, SETTLED, GIVEN_UP }

    private val state = AtomicReference(State.LOADING)

    private val timer = scope.launch {
        delay(timeoutMs)
        if (state.compareAndSet(State.LOADING, State.GIVEN_UP)) onTimeout()
    }

    /** Gives the renderer up without reporting anything, as when the ad is destroyed. */
    fun cancel() {
        state.set(State.GIVEN_UP)
        timer.cancel()
    }

    override fun onLoaded() {
        if (settle()) listener.onLoaded()
    }

    override fun onError(error: Throwable) {
        settle()
        forward { onError(error) }
    }

    override fun onImpression() = forward { onImpression() }

    override fun onClick() = forward { onClick() }

    override fun onClose() = forward { onClose() }

    override fun onReward() = forward { onReward() }

    private fun settle(): Boolean = state.compareAndSet(State.LOADING, State.SETTLED).also { if (it) timer.cancel() }

    private inline fun forward(event: AdRenderer.Listener.() -> Unit) {
        if (state.get() != State.GIVEN_UP) listener.event()
    }
}
