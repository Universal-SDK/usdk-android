package com.example.host

import org.bidon.rendering.AdRenderer

/**
 * A host app's listener whose `onLoaded` throws, as a bug in the host's code would. It lives outside `org.bidon`, as a
 * host app's code does, so its own frame never makes a crash ours: only the renderer's frames below it can.
 */
object ThrowingListener : AdRenderer.Listener {
    override fun onLoaded() = throw IllegalStateException("ours")
    override fun onImpression() = Unit
    override fun onClick() = Unit
    override fun onClose() = Unit
    override fun onReward() = Unit
    override fun onError(error: Throwable) = Unit
}
