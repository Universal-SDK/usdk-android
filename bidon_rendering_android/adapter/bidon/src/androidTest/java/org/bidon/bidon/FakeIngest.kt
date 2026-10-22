package org.bidon.bidon

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * A telemetry ingest on this device, over https with a certificate made for the test, that accepts every batch and
 * keeps its body. The renderer takes only `https` endpoints from config, so a plain-http fake would never be called.
 */
internal class FakeIngest : Closeable {

    private val bodies = CopyOnWriteArrayList<String>()

    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(CERTIFICATE).build().sslSocketFactory(), false)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == PATH) bodies += request.body.readUtf8()
                return MockResponse().setBody("{}")
            }
        }
        start()
    }

    val url: String = "https://localhost:${server.port}$PATH"

    /** Every batch received so far, oldest first. */
    fun batches(): List<JSONObject> = bodies.map(::JSONObject)

    /**
     * The first batch with an event of [auctionId], waiting up to [timeoutMillis] for it. [whileWaiting] runs about
     * twice a second meanwhile.
     */
    fun awaitBatchWith(auctionId: String, timeoutMillis: Long = 15_000, whileWaiting: () -> Unit = {}): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            batches().firstOrNull { auctionId in it.auctionIds() }?.let { return it }
            whileWaiting()
            Thread.sleep(500)
        }
        throw AssertionError("no batch with $auctionId within $timeoutMillis ms; got ${bodies.size} batch(es)")
    }

    override fun close() = server.shutdown()

    companion object {
        const val PATH = "/v2/telemetry"

        /** One certificate for every fake in the process, so trusting it once covers them all. */
        private val CERTIFICATE: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()

        /**
         * Makes every `HttpsURLConnection` in this process trust the fakes' certificate, as it trusts a public ingest's.
         * Returns what puts the platform's trust back.
         */
        fun trustInThisProcess(): () -> Unit {
            val previous: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(
                HandshakeCertificates.Builder().addTrustedCertificate(CERTIFICATE.certificate).build().sslSocketFactory(),
            )
            return { HttpsURLConnection.setDefaultSSLSocketFactory(previous) }
        }
    }
}

/** The auction ids of a batch's events. */
internal fun JSONObject.auctionIds(): List<String> {
    val events = getJSONArray("events")
    return List(events.length()) { events.getJSONObject(it).getJSONObject("envelope").optString("auction_id") }
}
