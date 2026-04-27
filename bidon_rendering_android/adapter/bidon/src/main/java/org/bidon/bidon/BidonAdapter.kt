package org.bidon.bidon

import android.content.Context
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.adapter.*
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.json.JSONObject

internal val BidonDemandId = DemandId("bidon")

@Suppress("unused")
internal class BidonAdapter :
    Adapter.Network,
    Initializable<BidonInitParameters>,
    AdProvider.Banner<BidonBannerAuctionParams>,
    AdProvider.Interstitial<BidonAuctionParams>,
    AdProvider.Rewarded<BidonAuctionParams> {

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

    override fun banner(): AdSource.Banner<BidonBannerAuctionParams> {
        return BidonBannerImpl()
    }

    override fun interstitial(): AdSource.Interstitial<BidonAuctionParams> {
        return BidonInterstitialImpl()
    }

    override fun rewarded(): AdSource.Rewarded<BidonAuctionParams> {
        return BidonRewardedImpl()
    }

    override fun parseConfigParam(json: String): BidonInitParameters {
        val obj = JSONObject(json)

        return BidonInitParameters(
            endpoint = obj.optString("endpoint"),
            apiKey = obj.optString("api_key"),
            debug = obj.optBoolean("debug", false)
        )
    }
}