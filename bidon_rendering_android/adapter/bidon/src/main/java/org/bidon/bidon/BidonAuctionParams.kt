package org.bidon.bidon

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.auction.models.AdUnit

internal sealed interface BidonAuctionParams : AdAuctionParams {

    val activity: Activity
    val adm: String
    val crtype: String
    val nurl: String?
    val burl: String?
    val adUnit: AdUnit
    val price: Double
}