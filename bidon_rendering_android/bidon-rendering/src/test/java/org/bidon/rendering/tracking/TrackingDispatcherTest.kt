package org.bidon.rendering.tracking

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Fires creative tracking pixels against a minimal local HTTP server.
 *
 * Every pixel is a billing or measurement signal for the buyer, so the two things that matter are
 * that a real URL is requested exactly once, and that the empty tracker elements creatives often
 * carry are skipped rather than sent as broken requests.
 */
class TrackingDispatcherTest {

    private lateinit var server: ServerSocket
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val firstRequest = CountDownLatch(1)

    @Before
    fun startServer() {
        server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        // "GET /pixel?x=1 HTTP/1.1" is recorded as "GET /pixel?x=1".
                        reader.readLine()?.let { requests += it.substringBeforeLast(" ") }
                        while (reader.readLine().orEmpty().isNotEmpty()) Unit
                        socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                    }
                    firstRequest.countDown()
                }
            } catch (_: SocketException) {
                // The server socket was closed at the end of the test.
            }
        }
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun url(pathAndQuery: String) = "http://127.0.0.1:${server.localPort}$pathAndQuery"

    @Test
    fun `a tracker URL is requested once with GET`() {
        TrackingDispatcher.fire(url("/pixel?event=firstQuartile"))

        assertTrue(firstRequest.await(5, TimeUnit.SECONDS))
        // Give a duplicate request the chance to arrive before counting.
        Thread.sleep(300)
        assertEquals(listOf("GET /pixel?event=firstQuartile"), requests.toList())
    }

    @Test
    fun `an empty or blank tracker is not requested`() {
        TrackingDispatcher.fire("")
        TrackingDispatcher.fire("   ")

        assertFalse(firstRequest.await(500, TimeUnit.MILLISECONDS))
        assertTrue(requests.isEmpty())
    }
}
