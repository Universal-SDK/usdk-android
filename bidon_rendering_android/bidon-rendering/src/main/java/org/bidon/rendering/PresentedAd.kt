package org.bidon.rendering

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import org.bidon.rendering.telemetry.AdEvents
import java.io.File
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

    /**
     * The DSP's close button: when it can be used and how it looks. Video ignores it, since it
     * has its own skip and end card controls.
     */
    val closeButton: CloseButtonConfig

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

    /**
     * Reports the reward at most once for this show. A recreated activity may call it again, and must not pay twice:
     * its saved state can predate a reward earned while the app was in the background.
     */
    fun onRewarded()
    fun onFailed(error: Throwable)

    /**
     * The ad's telemetry events, for what only the screen sees: a screen that finds no view, a video that fails before
     * its first frame, or an end card whose renderer dies. Every presentation provides them. Null when telemetry is off.
     */
    val events: AdEvents?
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

    private const val TAG = "BidonRenderer"

    /** The name never changes while the process lives, so it is read once. Null when it cannot be read. */
    private val currentProcessName: String? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                processNameFromCmdline(File("/proc/self/cmdline").readText())
            } catch (e: Exception) {
                Log.w(TAG, "Could not read /proc/self/cmdline, skipping process check", e)
                null
            }
        }
    }

    private val targetProcessNames = ConcurrentHashMap<Class<*>, String>()

    /**
     * Why an ad cannot be shown from this process, or `null` when it can: the error names both processes when
     * [targetActivityClass] is declared to run in another one. The check is skipped when either name cannot be read.
     */
    fun crossProcessError(context: Context, targetActivityClass: Class<*>): IllegalStateException? {
        val current = currentProcessName
        val target = targetProcessNames[targetActivityClass] ?: try {
            val component = ComponentName(context, targetActivityClass)
            context.packageManager.getActivityInfo(component, 0).processName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Target activity info not found in manifest", e)
            return null
        }.also { targetProcessNames[targetActivityClass] = it }

        return evaluateProcessNames(current, target)
    }

    /** The first argument of a /proc/self/cmdline read: the process name is NUL-terminated, arguments may follow. */
    @VisibleForTesting
    internal fun processNameFromCmdline(raw: String): String = raw.substringBefore('\u0000').trim()

    /** A null or blank [current] name, as /proc can give on API 24 to 27, is unknown, so it skips the check. */
    @VisibleForTesting
    internal fun evaluateProcessNames(current: String?, target: String): IllegalStateException? {
        if (current.isNullOrBlank()) return null
        if (current != target) {
            return IllegalStateException("The ad cannot be shown from process '$current': its screen runs in '$target'")
        }
        return null
    }
}
