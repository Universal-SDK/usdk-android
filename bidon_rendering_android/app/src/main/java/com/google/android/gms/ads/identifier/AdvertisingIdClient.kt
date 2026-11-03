package com.google.android.gms.ads.identifier

import android.content.Context
import org.bidon.app.settings.SdkSettings

/**
 * Test-only stand-in for the Play services advertising id client.
 *
 * Why this exists: a backend's test campaign can bid for one specific device advertising id only.
 * Against such a backend, any other id gets a no-bid, so the demo app can never render a real
 * creative. There is no public SDK API to set the advertising id.
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
        /**
         * The id comes from the Settings tab so it can be changed without a rebuild. Its default,
         * the device a test campaign targets, lives in res/values/settings.xml.
         */
        @JvmStatic
        fun getAdvertisingIdInfo(context: Context): Info = Info(
            advertisingId = SdkSettings.load(context).advertisingId,
            limitAdTracking = false
        )
    }
}
