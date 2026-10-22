package org.bidon.rendering.telemetry

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

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

    /** The first batch [matching], waiting up to [timeoutMillis]. [whileWaiting] runs about five times a second. */
    fun awaitBatch(timeoutMillis: Long = 15_000, whileWaiting: () -> Unit = {}, matching: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            batches().firstOrNull(matching)?.let { return it }
            whileWaiting()
            Thread.sleep(200)
        }
        throw AssertionError("no matching batch within $timeoutMillis ms; got ${bodies.size} batch(es): $bodies")
    }

    override fun close() = server.shutdown()

    companion object {
        const val PATH = "/v2/telemetry"

        /** One certificate for every fake in the process, so trusting it once covers them all. */
        private val CERTIFICATE: HeldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()

        /** The fakes' certificate, for a process of the test app that must trust it too. */
        val certificatePem: String get() = CERTIFICATE.certificatePem()

        /**
         * Makes every `HttpsURLConnection` in this process trust the certificate in [pem], as it trusts a public
         * ingest's. Returns what puts the platform's trust back.
         */
        fun trustInThisProcess(pem: String = certificatePem): () -> Unit {
            val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(
                HandshakeCertificates.Builder().addTrustedCertificate(pem.decodeCertificatePem()).build().sslSocketFactory(),
            )
            return { HttpsURLConnection.setDefaultSSLSocketFactory(previous) }
        }
    }
}

/** The events of a batch, as JSON objects. */
internal fun JSONObject.events(): List<JSONObject> {
    val events = getJSONArray("events")
    return List(events.length()) { events.getJSONObject(it) }
}

/** The event's `envelope.event_name`. */
internal val JSONObject.eventName: String get() = getJSONObject("envelope").getString("event_name")
