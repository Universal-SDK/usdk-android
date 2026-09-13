package org.bidon.bidon

import android.app.Activity
import org.bidon.rendering.CreativeType
import org.bidon.rendering.AdRenderingConfig
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.auction.models.AdUnit

sealed interface BidonAuctionParams : AdAuctionParams {
    val activity: Activity
    val adm: String
    val creativeType: CreativeType
    val rendering: AdRenderingConfig
    override val price: Double
    override val adUnit: AdUnit
}

data class BidonBannerAuctionParams(
    override val activity: Activity,
    override val adm: String,
    override val creativeType: CreativeType,
    override val rendering: AdRenderingConfig,
    override val price: Double,
    override val adUnit: AdUnit
) : BidonAuctionParams

data class BidonFullscreenAuctionParams(
    override val activity: Activity,
    override val adm: String,
    override val creativeType: CreativeType,
    override val rendering: AdRenderingConfig,
    override val price: Double,
    override val adUnit: AdUnit
) : BidonAuctionParams
