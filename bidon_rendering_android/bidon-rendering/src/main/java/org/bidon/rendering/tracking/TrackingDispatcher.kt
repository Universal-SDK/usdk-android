package org.bidon.rendering.tracking

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
object TrackingDispatcher {

    private const val TAG = "BidonTracking"
    private val executor = Executors.newFixedThreadPool(2)
    private var connectTimeoutMs = 5000
    private var readTimeoutMs = 5000

    /**
     * Запускаємо tracking pixel
     *
     * @param url - tracking URL (nurl or burl)
     * @param pixelName - ("nurl" / "burl")
     */
    fun fire(url: String?, pixelName: String = "tracking") {
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ $pixelName pixel URL is null/empty!")
            return
        }

        Log.d(TAG, "🔥 Firing $pixelName: $url")

        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                val startTime = System.currentTimeMillis()

                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs

                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) Bidon/1.0"
                )

                val responseCode = connection.responseCode
                val responseTime = System.currentTimeMillis() - startTime

                connection.inputStream?.close()

                when {
                    responseCode in 200..299 -> {
                        Log.d(TAG, """
                            ╔═══════════════════════════════════════════════╗
                            ║ ✅ $pixelName SUCCESS
                            ╠═══════════════════════════════════════════════╣
                            ║ Code: $responseCode
                            ║ Time: ${responseTime}ms
                            ║ URL: $url
                            ║ Timestamp: ${System.currentTimeMillis()}
                            ╚═══════════════════════════════════════════════╝
                        """.trimIndent())
                    }
                    responseCode in 300..399 -> {
                        Log.w(TAG, """
                            ╔═══════════════════════════════════════════════╗
                            ║ ⚠️ $pixelName REDIRECT
                            ╠═══════════════════════════════════════════════╣
                            ║ Code: $responseCode
                            ║ Time: ${responseTime}ms
                            ║ URL: $url
                            ╚═══════════════════════════════════════════════╝
                        """.trimIndent())
                    }
                    else -> {
                        Log.e(TAG, """
                            ╔═══════════════════════════════════════════════╗
                            ║ ❌ $pixelName FAILED
                            ╠═══════════════════════════════════════════════╣
                            ║ Code: $responseCode
                            ║ Time: ${responseTime}ms
                            ║ URL: $url
                            ╚═══════════════════════════════════════════════╝
                        """.trimIndent())
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, """
                    ╔═══════════════════════════════════════════════╗
                    ║ ⏱️ $pixelName TIMEOUT
                    ╠═══════════════════════════════════════════════╣
                    ║ URL: $url
                    ║ Error: Connection timeout (${connectTimeoutMs}ms)
                    ╚═══════════════════════════════════════════════╝
                """.trimIndent())

            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, """
                    ╔═══════════════════════════════════════════════╗
                    ║ 🌐 $pixelName NO NETWORK
                    ╠═══════════════════════════════════════════════╣
                    ║ URL: $url
                    ║ Error: No internet connection or DNS failure
                    ╚═══════════════════════════════════════════════╝
                """.trimIndent())

            } catch (e: Exception) {
                Log.e(TAG, """
                    ╔═══════════════════════════════════════════════╗
                    ║ ❌ $pixelName ERROR
                    ╠═══════════════════════════════════════════════╣
                    ║ URL: $url
                    ║ Error: ${e.javaClass.simpleName}
                    ║ Message: ${e.message}
                    ╚═══════════════════════════════════════════════╝
                """.trimIndent(), e)

            } finally {
                connection?.disconnect()
            }
        }
    }

    fun setTimeout(connectMs: Int, readMs: Int) {
        connectTimeoutMs = connectMs
        readTimeoutMs = readMs
        Log.d(TAG, "⚙️ Tracking timeout set: connect=${connectMs}ms, read=${readMs}ms")
    }
}