package org.bidon.rendering.vast

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.CloseButtonConfig
import org.bidon.rendering.PresentedAd
import org.bidon.rendering.PresentedAds
import org.bidon.rendering.AdRenderingConfig
import org.bidon.rendering.failShow
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.ErrorCode
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.vast.ui.VideoAdActivity
import java.util.concurrent.ConcurrentHashMap

internal class VastRenderer(
    private val context: Context,
    private val rendering: AdRenderingConfig,
    private val events: AdEvents?,
) : AdRenderer {

    companion object {
        /** The VAST error code for a response with no media file the device can play. */
        private const val VAST_NO_SUPPORTED_MEDIA = "403"

        /**
         * The parsed VAST document for the activity to pick up, under the same key as its
         * presentation handle. Looked up rather than taken, so the activity can find it again if
         * it is recreated; the entry is dropped when the ad closes or the renderer is destroyed.
         */
        internal val adRegistry = ConcurrentHashMap<String, VastAd>()

        internal fun getAd(key: String?): VastAd? = key?.let { adRegistry[it] }
    }

    private var vastAd: VastAd? = null
    private var listener: AdRenderer.Listener? = null
    private var loadJob: Job? = null

    /**
     * Key the video activity uses to find its way back here. Stays set after the screen closes, since an ad is shown
     * once; cleared only when the screen could not start, or by [destroy].
     */
    private var presentationKey: String? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun load(
        adm: String,
        listener: AdRenderer.Listener
    ) {
        this.listener = listener

        loadJob?.cancel()
        events?.loadStarted()

        loadJob = scope.launch {
            // Only the parse is guarded: an exception from the host's own callback is not a VAST error.
            val result = try {
                VastParser().parse(adm)
            } catch (e: CancellationException) {
                // A destroyed renderer cancels its load; that is not a failure to report.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            result
                .onSuccess { ad ->
                    vastAd = ad
                    events?.loadSucceeded()
                    listener.onLoaded()
                }
                .onFailure { error ->
                    val failure = VastLoadFailure.classify(error)
                    events?.loadFailed(failure.code, failure.externalCode, error)
                    listener.onError(error)
                }
        }
    }

    override fun attach(container: ViewGroup) {
    }

    override fun showFullscreen(activity: Activity) {
        events?.showRequested()
        // A destroyed renderer has dropped its ad, so it fails here as one that never loaded.
        val ad = vastAd ?: return failShow(events, listener, ErrorCode.SHOW_NOT_READY)
        if (presentationKey != null) return failShow(events, listener, ErrorCode.SHOW_TWICE)
        PresentedAds.crossProcessError(activity, VideoAdActivity::class.java)?.let {
            return failShow(events, listener, ErrorCode.SHOWN_FROM_OTHER_PROCESS, cause = it)
        }
        events?.showStarted()

        val media = MediaFileSelector
            .select(
                context = context,
                mediaFiles = ad.mediaFiles
            )
            .getOrElse { error ->
                return failShow(events, listener, ErrorCode.NO_PLAYABLE_MEDIA, VAST_NO_SUPPORTED_MEDIA, error)
            }

        val orientation = AdWindow.orientationInheritedFrom(activity)

        // Video builds its own player surface, so unlike HTML and MRAID there is no loaded view to
        // hand over - only the way back for the events.
        val key = PresentedAds.register(object : PresentedAd {
            override val contentView: View? get() = null

            // Video gates dismissal on the skip offset and draws its own controls.
            override val closeButton: CloseButtonConfig get() = rendering.closeButton
            override val lockedOrientation: Int = orientation
            override var closeRequester: (() -> Unit)? = null
            override val events: AdEvents? get() = this@VastRenderer.events

            override fun onImpression() {
                events?.rendered()
                listener?.onImpression()
            }
            override fun onClicked() = run { listener?.onClick(); Unit }
            // The end card, the only WebView, goes with the screen, which forgets this presentation as it closes.
            override fun onClosed() {
                events?.closed()
                listener?.onClose()
            }
            override fun onRewarded() = run { listener?.onReward(); Unit }
            override fun onFailed(error: Throwable) = run { listener?.onError(error); Unit }
        })
        presentationKey = key
        adRegistry[key] = ad

        try {
            VideoAdActivity.start(
                context = activity,
                mediaUrl = media.url,
                clickThroughUrl = ad.videoClicks?.clickThroughUrl,
                fallbackSkipOffsetMs = rendering.skipOffsetMs,
                adKey = key
            )
        } catch (e: Exception) {
            PresentedAds.unregister(key)
            adRegistry.remove(key)
            presentationKey = null
            failShow(events, listener, ErrorCode.AD_SCREEN_NOT_STARTED, cause = e)
        }

        // The impression is reported by the activity once the video starts playing, not here.
    }

    override fun getView(): View? = null

    override fun destroy() {
        loadJob?.cancel()
        loadJob = null
        scope.cancel()

        PresentedAds.unregister(presentationKey)
        presentationKey?.let { adRegistry.remove(it) }
        presentationKey = null

        vastAd = null
        listener = null
    }
}
