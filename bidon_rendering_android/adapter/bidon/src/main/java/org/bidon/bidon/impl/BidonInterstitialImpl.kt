package org.bidon.bidon.impl

import android.app.Activity
import android.content.Intent
import org.bidon.bidon.BidonFullscreenAuctionParams
import org.bidon.rendering.BidonRenderer
import org.bidon.rendering.AdRenderer
import org.bidon.rendering.tracking.TrackingDispatcher
import org.bidon.rendering.ui.FullscreenAdActivity
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

internal class BidonInterstitialImpl :
    AdSource.Interstitial<BidonFullscreenAuctionParams> {

    private var renderer: AdRenderer? = null
    private var isShown = false
    private var isImpressionSent = false

    override val isAdReadyToShow: Boolean
        get() = renderer != null

    override fun load(adParams: BidonFullscreenAuctionParams) {

        logInfo(TAG, "load: $adParams")

        val adm = adParams.adm
        val crtype = adParams.crtype
        val nurl = adParams.nurl
        val burl = adParams.burl

        renderer = BidonRenderer.createRendererByType(
            context = adParams.activity,
            crtype = crtype
        )

        nurl?.let {
            TrackingDispatcher.fire(it)
        }

        renderer?.load(
            adm = adm,
            nurl = nurl ?: "",
            onLoaded = {
                emit(AdEvent.Fill(adParams))
            },
            onClick = {
                emit(AdEvent.Click(adParams))
            },
            onError = { error ->
                emit(AdEvent.LoadFailed(BidonError.Internal(error.message ?: "Render error")))
            }
        )

        pendingBurl = burl
    }

    private var pendingBurl: String? = null

    override fun show(activity: Activity) {

        if (renderer == null) {
            emit(AdEvent.ShowFailed(BidonError.AdNotReady))
            return
        }

        if (isShown) return
        isShown = true

        logInfo(TAG, "show")

        val burl = pendingBurl

        val intent = Intent(activity, FullscreenAdActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        activity.startActivity(intent)

        if (!isImpressionSent) {
            isImpressionSent = true

            burl?.let {
                TrackingDispatcher.fire(it)
            }

            emit(AdEvent.Impression)
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy")

        renderer?.destroy()
        renderer = null

        isShown = false
        isImpressionSent = false
        pendingBurl = null
    }

    private fun emit(event: AdEvent) {
        logInfo(TAG, "event: $event")
    }

    companion object {
        private const val TAG = "BidonInterstitial"
    }
}