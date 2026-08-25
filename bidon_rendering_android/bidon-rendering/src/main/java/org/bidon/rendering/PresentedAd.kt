package org.bidon.rendering

import android.view.View
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The handle a fullscreen ad activity uses to talk back to the renderer that started it.
 *
 * An Activity is started with an Intent, so it cannot be handed an object reference. Each renderer
 * registers one of these, puts the returned key in the Intent, and the activity looks it up.
 *
 * Without it the ad activities were a dead end: nothing that happened inside them - close, click,
 * reward, or a render failure - could reach the adapter, so interstitial and rewarded could never
 * complete.
 */
internal interface PresentedAd {

    /**
     * The view the renderer already built and loaded, for the activity to adopt. Null for video,
     * which builds its own player surface.
     *
     * The activity must not build its own: doing that threw away the loaded WebView, reloaded the
     * markup a second time, and lost the click handling attached to the original.
     */
    val contentView: View?

    /** How long after the ad appears before the close button can be used. */
    val closeDelayMs: Long

    /**
     * The orientation this ad locks to, inherited from the publisher's activity when it was
     * shown. Resolved at show time, because that is when the publisher's own orientation and the
     * current screen position are known.
     */
    val lockedOrientation: Int

    /**
     * Set by the hosting activity so the creative can dismiss itself. An MRAID creative calls
     * mraid.close(), and that has to reach the activity showing it rather than the renderer.
     */
    var closeRequester: (() -> Unit)?

    fun onImpression()
    fun onClicked()
    fun onClosed()
    fun onRewarded()
    fun onFailed(error: Throwable)
}

internal object PresentedAds {

    const val EXTRA_KEY: String = "org.bidon.rendering.presented_ad_key"

    private val entries = ConcurrentHashMap<String, PresentedAd>()

    fun register(ad: PresentedAd): String {
        val key = UUID.randomUUID().toString()
        entries[key] = ad
        return key
    }

    /**
     * Looks up without removing. The activity needs the handle for its whole lifetime, and is
     * recreated on a configuration change, at which point it looks the same key up again. Removal
     * is explicit: the activity drops the entry when it finishes, and the renderer drops it in
     * destroy() so an ad that is never shown does not leak.
     */
    fun get(key: String?): PresentedAd? = key?.let { entries[it] }

    fun unregister(key: String?) {
        key?.let { entries.remove(it) }
    }
}
