package org.bidon.rendering.telemetry

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/** Every row of the retry table of D-028, against a real HTTP server. */
class HttpTelemetryClientTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = MockWebServer().apply { start() }

    @After
    fun stopServer() = server.shutdown()

    private fun client(readTimeoutMillis: Int = HttpTelemetryClient.READ_TIMEOUT_MILLIS) =
        HttpTelemetryClient(endpoint(server.url("/v2/telemetry").toString()), readTimeoutMillis = readTimeoutMillis)

    private fun answer(status: Int, retryAfter: String? = null): PostResult {
        server.enqueue(MockResponse().setResponseCode(status).apply { retryAfter?.let { setHeader("Retry-After", it) } })
        return client().post(BODY)
    }

    @Test
    fun `the request is a POST of the body as it was given, with the contract's headers`() {
        server.enqueue(MockResponse().setResponseCode(202))

        client().post(BODY)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v2/telemetry", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals(SDK_VERSION, request.getHeader("X-Bidon-Version"))
        assertArrayEquals(BODY, request.body.readByteArray())
    }

    @Test
    fun `a 2xx answer means the server has the batch`() {
        for (status in listOf(200, 202, 204)) {
            assertEquals("HTTP $status", PostResult.Accepted, answer(status))
        }
    }

    @Test
    fun `a 413 answer means the batch is too large`() {
        assertEquals(PostResult.TooLarge, answer(413))
    }

    @Test
    fun `server errors, request timeouts and throttling are retried`() {
        for (status in listOf(408, 429, 500, 502, 503, 599)) {
            val result = answer(status)
            assertTrue("HTTP $status gave $result", result is PostResult.Retry)
            assertNull("HTTP $status", (result as PostResult.Retry).retryAfterMillis)
        }
    }

    @Test
    fun `Retry-After in seconds is passed on in milliseconds`() {
        assertEquals(7_000L, (answer(429, retryAfter = "7") as PostResult.Retry).retryAfterMillis)
        assertEquals(120_000L, (answer(503, retryAfter = " 120 ") as PostResult.Retry).retryAfterMillis)
        assertEquals(0L, (answer(503, retryAfter = "0") as PostResult.Retry).retryAfterMillis)
    }

    @Test
    fun `a Retry-After that is not a number of seconds counts as absent`() {
        for (value in listOf("Wed, 21 Oct 2026 07:28:00 GMT", "-5", "soon", "")) {
            assertNull(value, (answer(503, retryAfter = value) as PostResult.Retry).retryAfterMillis)
        }
    }

    @Test
    fun `any other answer means the server refused the batch`() {
        for (status in listOf(400, 401, 403, 404, 422, 301, 302, 307)) {
            val result = answer(status)
            assertTrue("HTTP $status gave $result", result is PostResult.Rejected)
            assertEquals(status, (result as PostResult.Rejected).status)
        }
    }

    /**
     * Pins the outcome, not the `instanceFollowRedirects` line: a body sent in fixed-length streaming mode cannot be
     * sent again, so the JDK does not follow a redirect of it either way. The line states the intent for whichever
     * HTTP stack the device has.
     */
    @Test
    fun `a redirect is refused and not followed, whatever its kind`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/v2/telemetry") {
                    MockResponse().setResponseCode(redirect).setHeader("Location", server.url("/elsewhere"))
                } else {
                    MockResponse().setResponseCode(202)
                }
        }

        for (status in listOf(301, 302, 303, 307, 308)) {
            redirect = status
            val result = client().post(BODY)
            assertTrue("HTTP $status gave $result", result is PostResult.Rejected)
        }

        assertEquals(5, server.requestCount)
    }

    @Volatile
    private var redirect = 0

    @Test
    fun `a network the app may not use is retried, and does not escape`() {
        // What Android throws when a firewall or a missing INTERNET permission denies the socket.
        val denied = HttpTelemetryClient(endpoint("https://example.com/v2/telemetry"), openConnection = { url ->
            object : HttpURLConnection(url) {
                override fun connect() = throw SecurityException("Permission denied (missing INTERNET permission?)")
                override fun getOutputStream(): OutputStream = connect().let { error("unreachable") }
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })

        assertTrue(denied.post(BODY) is PostResult.Retry)
    }

    @Test
    fun `a connection dropped before the answer is retried`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertTrue(client().post(BODY) is PostResult.Retry)
    }

    @Test
    fun `a server that never answers is retried once the read times out`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertTrue(client(readTimeoutMillis = 200).post(BODY) is PostResult.Retry)
    }

    @Test
    fun `a server that cannot be reached is retried`() {
        // Nothing can listen on port 0 (binding to it picks another port), so no other test can take it.
        assertTrue(HttpTelemetryClient(endpoint("http://127.0.0.1:0/v2/telemetry")).post(BODY) is PostResult.Retry)
    }

    @Test
    fun `an answer that is not HTTP is retried`() {
        assertTrue(HttpTelemetryClient.resultOf(-1, retryAfter = null) is PostResult.Retry)
    }

    private companion object {
        const val SDK_VERSION = "0.14.0"
        val BODY = """{"app":{"key":"k","bundle":"b"},"session":{"id":"s"},"events":[{"é":"✓"}]}""".toByteArray()

        fun endpoint(url: String) = TelemetryEndpoint.of(url, appKey = "key", bundle = "com.example", SDK_VERSION)!!
    }
}
