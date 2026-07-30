package com.google.android.gms.ads.identifier

import android.content.Context

/**
 * Test-only stand-in for the Play services advertising id client.
 *
 * Why this exists: the Adikteev test campaign on the staging backend only bids for one specific
 * device advertising id. Without it the auction returns a no-bid, so the demo app can never render
 * a real creative. There is no public SDK API to set the advertising id.
 *
 * How it works: the BidOn SDK looks this class up by name with reflection
 * (see AdvertisingDataImpl.getGoogleAdId) rather than linking against it. This module does not
 * depend on Play services, so that lookup normally fails and the SDK falls back to an all-zero id.
 * Declaring the class here with the same name and method shapes makes the lookup succeed and hands
 * the SDK the test id through its own normal code path. Nothing in the SDK or the adapter is
 * patched.
 *
 * Only for the demo app. Do not ship this in the library, and do not add a real Play services
 * dependency to this module while this file exists - the class names would collide.
 */
class AdvertisingIdClient {

    class Info(
        private val advertisingId: String,
        private val limitAdTracking: Boolean,
    ) {
        fun getId(): String = advertisingId

        fun isLimitAdTrackingEnabled(): Boolean = limitAdTracking
    }

    companion object {
        /** The device the Adikteev test campaign targets. */
        const val TEST_ADVERTISING_ID: String = "013b8c47-8beb-41b4-aefe-60fe466f9fd8"

        @JvmStatic
        fun getAdvertisingIdInfo(context: Context): Info {
            println("hop: serving test advertising id $TEST_ADVERTISING_ID")
            return Info(advertisingId = TEST_ADVERTISING_ID, limitAdTracking = false)
        }
    }
}
