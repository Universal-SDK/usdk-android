package org.bidon.bidon

import android.content.Context
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.bidon.impl.BidonBannerImpl
import org.bidon.bidon.impl.BidonInterstitialImpl
import org.bidon.bidon.impl.BidonRewardedImpl
import org.json.JSONObject

internal val BidonDemandId = DemandId("adikteev")

@Suppress("unused")
class BidonAdapter :
    Adapter.Network,
    Initializable<BidonInitParameters>,
    AdProvider.Banner<BidonBannerAuctionParams>,
    AdProvider.Interstitial<BidonFullscreenAuctionParams>,
    AdProvider.Rewarded<BidonFullscreenAuctionParams> {

    override val demandId: DemandId = BidonDemandId

    override val adapterInfo = AdapterInfo(
        adapterVersion = "1.0.0",
        sdkVersion = "rendering-1.0.0"
    )

    override suspend fun init(
        context: Context,
        configParams: BidonInitParameters
    ) {
        BidonRenderer.init(
            context = context,
            config = configParams.toRendererConfig()
        )
    }

    override fun banner(): AdSource.Banner<BidonBannerAuctionParams> =
        BidonBannerImpl()

    override fun interstitial(): AdSource.Interstitial<BidonFullscreenAuctionParams> =
        BidonInterstitialImpl()

    override fun rewarded(): AdSource.Rewarded<BidonFullscreenAuctionParams> =
        BidonRewardedImpl()

    override fun parseConfigParam(json: String): BidonInitParameters {
        val obj = JSONObject(json)
        return BidonInitParameters(
            endpoint = obj.optString("endpoint").ifBlank { null },
            apiKey = obj.optString("api_key").ifBlank { null },
            debug = obj.optBoolean("debug", false),
            timeoutMs = obj.optLong("timeout_ms", 5000L)
        )
    }
}