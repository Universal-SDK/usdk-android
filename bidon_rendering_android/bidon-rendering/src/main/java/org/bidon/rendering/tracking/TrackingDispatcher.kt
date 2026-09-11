package org.bidon.rendering.tracking

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fires tracking pixels that belong to the creative, such as VAST progress events, from the
 * device. Auction callbacks (nurl, burl, lurl) never go through here: the server fires those.
 */
object TrackingDispatcher {

    private const val TAG = "BidonTracking"
    private const val TIMEOUT_MS = 5000
    private val executor = Executors.newFixedThreadPool(2)

    fun fire(url: String) {
        // Creatives often carry empty tracker elements; there is nothing to fire for those.
        if (url.isBlank()) return

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) Bidon/1.0"
                )

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Tracking pixel returned HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tracking pixel failed: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
    }
}
