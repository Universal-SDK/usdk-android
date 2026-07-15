package org.bidon.rendering.vast

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.vast.ui.VideoAdActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VastRenderer(
    private val context: Context
) : AdRenderer {

    companion object {
        internal val adRegistry = ConcurrentHashMap<String, VastAd>()

        internal fun putAd(ad: VastAd): String {
            val key = UUID.randomUUID().toString()
            adRegistry[key] = ad
            return key
        }

        internal fun takeAd(key: String): VastAd? {
            return adRegistry.remove(key)
        }
    }

    private var vastAd: VastAd? = null
    private var listener: AdRenderer.Listener? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun load(
        adm: String,
        listener: AdRenderer.Listener
    ) {
        this.listener = listener

        loadJob?.cancel()

        loadJob = scope.launch {
            try {
                val result = VastParser().parse(adm)

                result
                    .onSuccess { ad ->
                        vastAd = ad
                        listener.onLoaded()
                    }
                    .onFailure { error ->
                        listener.onError(error)
                    }

            } catch (e: Exception) {
                listener.onError(e)
            }
        }
    }

    override fun attach(container: ViewGroup) {
    }

    override fun showFullscreen(activity: Activity) {
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
                listener?.onError(error)
                return
            }

        val adKey = putAd(ad)

        VideoAdActivity.start(
            context = activity,
            mediaUrl = media.url,
            clickThroughUrl = ad.videoClicks?.clickThroughUrl,
            skipOffsetMs = ad.skipOffsetMs ?: 5000L,
            adKey = adKey
        )
        listener?.onImpression()
    }

    override fun getView(): View? = null

    override fun destroy() {
        loadJob?.cancel()
        loadJob = null
        scope.cancel()
        vastAd = null
        listener = null
    }
}