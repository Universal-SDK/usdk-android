package org.bidon.app

import org.bidon.sdk.ads.Ad

/** The parts of a won bid worth reading in a log line: who won, for how much, via which bid type. */
fun Ad.describe(): String = "$networkName $price ${currencyCode ?: ""} ($bidType)"
