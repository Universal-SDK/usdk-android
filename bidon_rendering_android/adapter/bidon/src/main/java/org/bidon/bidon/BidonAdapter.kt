package org.bidon.bidon

import android.content.Context
import org.bidon.rendering.BidonRenderer
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.bidon.impl.BidonBannerImpl
import org.bidon.bidon.impl.BidonInterstitialImpl
import org.bidon.bidon.impl.BidonRewardedImpl
import org.json.JSONObject

internal val BidonDemandId = DemandId("adikteev")

/**
 * The auction response carries the creative in `ext.payload` but does not say what kind it is,
 * so we detect it from the markup.
 */
internal fun creativeTypeOf(adm: String): String = when {
    adm.contains("<VAST", ignoreCase = true) -> "VAST"
    adm.contains("mraid.js", ignoreCase = true) -> "MRAID"
    else -> "HTML"
}

@Suppress("unused")
class BidonAdapter :
    Adapter.Bidding,
    Initializable<BidonInitParameters>,
    AdProvider.Banner<BidonBannerAuctionParams>,
    AdProvider.Interstitial<BidonFullscreenAuctionParams>,
    AdProvider.Rewarded<BidonFullscreenAuctionParams> {

    override val demandId: DemandId = BidonDemandId

    override val adapterInfo = AdapterInfo(
        adapterVersion = "1.0.0",
        sdkVersion = "rendering-1.0.0"
    )

    // The adikteev bid token = sdk_instance_id from the config response. The SDK sends it in
    // the auction request's demands so the backend runs the adikteev bid.
    private var sdkInstanceId: String? = null

    override suspend fun init(
        context: Context,
        configParams: BidonInitParameters
    ) {
        sdkInstanceId = configParams.sdkInstanceId
        BidonRenderer.init(
            context = context,
            config = configParams.toRendererConfig()
        )
    }

    override suspend fun getToken(adTypeParam: AdTypeParam): String? = sdkInstanceId

    override fun banner(): AdSource.Banner<BidonBannerAuctionParams> =
        BidonBannerImpl()

    override fun interstitial(): AdSource.Interstitial<BidonFullscreenAuctionParams> =
        BidonInterstitialImpl()

    override fun rewarded(): AdSource.Rewarded<BidonFullscreenAuctionParams> =
        BidonRewardedImpl()

    override fun parseConfigParam(json: String): BidonInitParameters {
        val obj = JSONObject(json)
        return BidonInitParameters(
            sdkInstanceId = obj.optString("sdk_instance_id").ifBlank { null },
            endpoint = obj.optString("endpoint").ifBlank { null },
            apiKey = obj.optString("api_key").ifBlank { null },
            debug = obj.optBoolean("debug", false),
            timeoutMs = obj.optLong("timeout_ms", 5000L)
        )
    }
}