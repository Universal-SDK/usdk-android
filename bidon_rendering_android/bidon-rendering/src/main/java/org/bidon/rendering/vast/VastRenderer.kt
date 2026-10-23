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
import org.bidon.rendering.telemetry.AdEvents
import org.bidon.rendering.telemetry.LoadFailure
import org.bidon.rendering.ui.AdWindow
import org.bidon.rendering.vast.ui.VideoAdActivity
import java.util.concurrent.ConcurrentHashMap

internal class VastRenderer(
    private val context: Context,
    private val rendering: AdRenderingConfig,
    private val events: AdEvents?,
) : AdRenderer {

    companion object {
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

    /** Key the video activity uses to find its way back here. */
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
                    listener.onLoaded()
                }
                .onFailure { error ->
                    events?.loadFailed(LoadFailure.VAST_ERROR, (error as? VastParsingException)?.vastError?.code)
                    listener.onError(error)
                }
        }
    }

    override fun attach(container: ViewGroup) {
    }

    override fun showFullscreen(activity: Activity) {
        events?.showRequested()
        val ad = vastAd

        if (ad == null) {
            listener?.onError(IllegalStateException("VAST not loaded"))
            return
        }

        val media = MediaFileSelector
            .select(
                context = context,
                mediaFiles = ad.mediaFiles
            )
            .getOrElse { error ->
                events?.loadFailed(LoadFailure.NO_MEDIA_FILE)
                listener?.onError(error)
                return
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
            override fun onClosed() = run { listener?.onClose(); Unit }
            override fun onRewarded() = run { listener?.onReward(); Unit }
            override fun onFailed(error: Throwable) = run { listener?.onError(error); Unit }
        })
        presentationKey = key
        adRegistry[key] = ad

        VideoAdActivity.start(
            context = activity,
            mediaUrl = media.url,
            clickThroughUrl = ad.videoClicks?.clickThroughUrl,
            fallbackSkipOffsetMs = rendering.skipOffsetMs,
            adKey = key
        )

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
