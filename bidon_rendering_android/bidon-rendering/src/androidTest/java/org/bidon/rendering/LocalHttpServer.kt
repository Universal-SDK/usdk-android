package org.bidon.rendering

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A minimal HTTP server on the device's loopback interface, for tests that need the renderer's real
 * connections: a wrapped VAST document, a video, a tracking pixel. It serves canned bodies by path,
 * can leave a path hanging, answers 404 for anything else, and records the path and query of every
 * request.
 */
class LocalHttpServer : Closeable {

    private companion object {
        const val HOST = "127.0.0.1"
    }

    private class Body(val contentType: String, val bytes: ByteArray)

    // Bound to 127.0.0.1 by address: on Android the loopback address is the IPv6 ::1.
    private val socket = ServerSocket(0, 0, InetAddress.getByName(HOST))
    private val bodies = ConcurrentHashMap<String, Body>()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val stalled = ConcurrentHashMap.newKeySet<String>()
    private val closed = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val connection = socket.accept()
                    // One thread per connection, so a pixel is not held up behind a video download.
                    thread(isDaemon = true) { respond(connection) }
                }
            } catch (_: SocketException) {
                // The server socket was closed at the end of the test.
            }
        }
    }

    fun url(pathAndQuery: String): String = "http://$HOST:${socket.localPort}$pathAndQuery"

    fun serve(path: String, contentType: String, bytes: ByteArray) {
        bodies[path] = Body(contentType, bytes)
    }

    /** Accepts requests for [path] and never answers them, like a server that stopped responding. */
    fun stall(path: String) {
        stalled += path
    }

    /** The path and query of every request for [path], whatever its query. */
    fun requestsTo(path: String): List<String> = synchronized(requests) {
        requests.filter { it.substringBefore("?") == path }
    }

    fun requestCount(path: String): Int = requestsTo(path).size

    override fun close() {
        closed.countDown()
        socket.close()
    }

    private fun respond(connection: Socket) {
        connection.use {
            try {
                val reader = it.getInputStream().bufferedReader()
                // "GET /pixel?x=1 HTTP/1.1" is recorded as "/pixel?x=1".
                val pathAndQuery = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
                while (reader.readLine().orEmpty().isNotEmpty()) Unit
                requests += pathAndQuery

                if (pathAndQuery.substringBefore("?") in stalled) {
                    closed.await()
                    return
                }

                val body = bodies[pathAndQuery.substringBefore("?")]
                val head = if (body == null) {
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: ${body.contentType}\r\n" +
                        "Content-Length: ${body.bytes.size}\r\nConnection: close\r\n\r\n"
                }
                it.getOutputStream().apply {
                    write(head.toByteArray())
                    body?.let { served -> write(served.bytes) }
                    flush()
                }
            } catch (_: SocketException) {
                // The client went away mid-response, as a player does when it is released.
            }
        }
    }
}
