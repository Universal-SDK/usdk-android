package org.bidon.rendering.tracking

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TrackingDispatcher {

    private const val TAG = "BidonTracking"

    private val executor = Executors.newFixedThreadPool(2)

    private var connectTimeoutMs = 3000
    private var readTimeoutMs = 3000

    fun fire(url: String?) {
        if (url.isNullOrEmpty()) return

        Log.d(TAG, "📡 Firing tracking URL: $url")

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs

                val responseCode = connection.responseCode

                connection.inputStream?.close()

                Log.d(TAG, "✅ Tracking success: $url (code: $responseCode)")

            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "⏱️ Tracking timeout: $url")

            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "🌐 No network for tracking: $url")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Tracking failed: $url", e)

            } finally {
                connection?.disconnect()
            }
        }
    }

    fun setTimeout(connectMs: Int, readMs: Int) {
        connectTimeoutMs = connectMs
        readTimeoutMs = readMs
    }
}