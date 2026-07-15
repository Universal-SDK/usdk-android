package org.bidon.bidon

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.auction.models.LineItem

sealed interface BidonAuctionParams : AdAuctionParams {
    val activity: Activity
    val adm: String
    val crtype: String
    val nurl: String?
    val burl: String?
    override val price: Double
    override val lineItem: LineItem?
}

data class BidonBannerAuctionParams(
    override val activity: Activity,
    override val adm: String,
    override val crtype: String,
    override val nurl: String?,
    override val burl: String?,
    override val price: Double,
    override val lineItem: LineItem? = null
) : BidonAuctionParams

data class BidonFullscreenAuctionParams(
    override val activity: Activity,
    override val adm: String,
    override val crtype: String,
    override val nurl: String?,
    override val burl: String?,
    override val price: Double,
    override val lineItem: LineItem? = null
) : BidonAuctionParams