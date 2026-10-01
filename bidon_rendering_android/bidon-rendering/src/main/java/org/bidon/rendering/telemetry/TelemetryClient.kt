package org.bidon.rendering.telemetry

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit

/** Sends one request body to the ingest endpoint and says what the answer means for the events in it. */
internal fun interface TelemetryClient {

    fun post(body: ByteArray): PostResult
}

/** What an answer from the ingest endpoint means for the batch that was sent. */
internal sealed interface PostResult {

    /** The server has the batch. */
    object Accepted : PostResult

    /** The batch is over the server's size limits. Smaller batches of the same events may go through. */
    object TooLarge : PostResult

    /** The server refused the batch, and would refuse it again. */
    class Rejected(val status: Int) : PostResult

    /**
     * The batch did not get through, and may later: no answer, or an answer that says try again.
     *
     * @property retryAfterMillis how long the server asked the client to wait, when it said.
     */
    class Retry(val retryAfterMillis: Long?) : PostResult
}

/**
 * [TelemetryClient] over [HttpURLConnection], so the renderer adds no dependency to the host app.
 *
 * A request that cannot be completed is [PostResult.Retry]: any I/O failure, timeouts included, and a network the app
 * may not use, which Android reports as a [SecurityException] (a firewall, or a host app without the INTERNET
 * permission). The server deduplicates on each event's `event_id`, so a request that did arrive but whose answer was
 * lost costs a duplicate at most. Redirects are not followed: the endpoint is known, and a redirected POST is not the
 * request that was sent.
 *
 * The timeouts bound connecting and each read. They do not bound a DNS lookup or writing the body, which the platform
 * gives no timeout, so a network that accepts a connection and then stalls can hold the telemetry thread longer.
 * Events emitted meanwhile wait in the handoff, and are counted as overflow once it is full.
 */
internal class HttpTelemetryClient(
    private val endpoint: TelemetryEndpoint,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    /** Opens the connection. A parameter only so tests can stand in for a platform that refuses the socket. */
    private val openConnection: (URL) -> URLConnection = URL::openConnection,
) : TelemetryClient {

    override fun post(body: ByteArray): PostResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(endpoint.url) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Bidon-Version", endpoint.sdkVersion)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            resultOf(connection.responseCode, connection.getHeaderField("Retry-After"))
        } catch (_: IOException) {
            PostResult.Retry(retryAfterMillis = null)
        } catch (_: SecurityException) {
            PostResult.Retry(retryAfterMillis = null)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {

        /** Short, so that a send started as the app goes to the background has a chance to finish before it is frozen. */
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000

        /**
         * What an HTTP [status] means for the batch. Only these are worth retrying: no valid answer, a request
         * timeout (408), throttling (429) and server errors (5xx). Any other answer would be the same next time.
         */
        fun resultOf(status: Int, retryAfter: String?): PostResult = when {
            status in 200..299 -> PostResult.Accepted
            status == 413 -> PostResult.TooLarge
            status < 100 || status == 408 || status == 429 || status in 500..599 ->
                PostResult.Retry(retryAfterMillis(retryAfter))
            else -> PostResult.Rejected(status)
        }

        /**
         * A `Retry-After` given in seconds, in milliseconds. The HTTP-date form is treated as absent: honouring it
         * depends on the device's clock being right.
         */
        private fun retryAfterMillis(value: String?): Long? =
            value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { TimeUnit.SECONDS.toMillis(it) }
    }
}
