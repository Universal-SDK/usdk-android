package org.bidon.bidon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class RendererLoadDeadline(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val onTimeout: () -> Unit
) {

    private var timeoutJob: Job? = null
    private val finished = AtomicBoolean(false)

    fun start() {
        timeoutJob?.cancel()
        finished.set(false)

        timeoutJob = scope.launch {
            delay(timeoutMs)

            if (finished.compareAndSet(false, true)) {
                onTimeout()
            }
        }
    }

    fun finish(): Boolean {
        if (finished.compareAndSet(false, true)) {
            timeoutJob?.cancel()
            timeoutJob = null
            return true
        }

        return false
    }

    fun cancel() {
        finished.set(true)
        timeoutJob?.cancel()
        timeoutJob = null
    }
}