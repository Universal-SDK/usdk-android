package org.bidon.rendering.vast

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.LocalHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Why a wrapper cannot be followed, told apart by the error the parser gives (BID-136 AC 8): a server that answers
 * with an error status is not a document that cannot be parsed, and a server that does not answer is not a wrapper that
 * has no address. Each failure keeps its cause, so the renderer can report who is to blame.
 *
 * Instrumented because the parser is built on android.util.Xml, which has no implementation on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class VastParserWrapperFailureTest {

    private val server = LocalHttpServer()

    @After
    fun stopServer() {
        server.close()
    }

    private fun wrapper(address: String?) = """
        <VAST version="4.2"><Ad id="1"><Wrapper><AdSystem>test</AdSystem>
        ${address?.let { "<VASTAdTagURI><![CDATA[$it]]></VASTAdTagURI>" }.orEmpty()}
        </Wrapper></Ad></VAST>
    """.trimIndent()

    private fun failure(xml: String): VastParsingException {
        val result = runBlocking { VastParser().parse(xml) }
        val error = result.exceptionOrNull()
        assertNotNull("the wrapper parsed", error)
        assertTrue("not a parsing failure: $error", error is VastParsingException)
        return error as VastParsingException
    }

    @Test
    fun aWrapperTheServerAnswersWith404FailsWithThatStatus() {
        server.respondWith("/wrapped.xml", 404)

        val error = failure(wrapper(server.url("/wrapped.xml")))

        assertEquals(301, error.vastError.code)
        assertEquals(404, (error.cause as VastWrapperHttpException).status)
    }

    @Test
    fun aWrapperTheServerAnswersWith500FailsWithThatStatus() {
        server.respondWith("/wrapped.xml", 500)

        val error = failure(wrapper(server.url("/wrapped.xml")))

        assertEquals(301, error.vastError.code)
        assertEquals(500, (error.cause as VastWrapperHttpException).status)
    }

    @Test
    fun anAnswerWithAStatusOutside200To299IsAFailure() {
        listOf(300, 304, 400, 403, 429, 503).forEach { status ->
            server.respondWith("/status-$status.xml", status)

            val error = failure(wrapper(server.url("/status-$status.xml")))

            assertEquals("status $status", 301, error.vastError.code)
            assertEquals("status $status", status, (error.cause as VastWrapperHttpException).status)
        }
    }

    @Test
    fun aSuccessfulAnswerIsNotAStatusFailure() {
        listOf(204, 299).forEach { status ->
            server.respondWith("/status-$status.xml", status)

            val error = failure(wrapper(server.url("/status-$status.xml")))

            assertTrue("status $status: ${error.cause}", error.cause !is VastWrapperHttpException)
        }
    }

    @Test
    fun aWrapperTheServerDoesNotAnswerFailsWithTheConnectionError() {
        val error = failure(wrapper("http://127.0.0.1:${closedPort()}/wrapped.xml"))

        assertEquals(301, error.vastError.code)
        assertTrue("cause: ${error.cause}", error.cause is ConnectException)
        assertTrue(error.cause is IOException)
        assertTrue(error.cause !is VastWrapperHttpException)
    }

    @Test
    fun aWrapperWithoutAnAddressIsNeitherAnInvalidDocumentNorAFetchFailure() {
        val error = failure(wrapper(address = null))

        assertEquals(300, error.vastError.code)
    }

    @Test
    fun aBlankMalformedNonHttpOrHostlessAddressIsAMissingUri() {
        listOf(
            "", "   ", "not a url", "wrapped.xml", "ftp://example.com/wrapped.xml", "file:///etc/hosts",
            "javascript:alert(1)", "http:///wrapped.xml", "https://:443/wrapped.xml",
        ).forEach { address ->
                val error = failure(wrapper(address))

                assertEquals("address <$address>", 300, error.vastError.code)
            }
    }

    @Test
    fun anHttpsAddressIsFetched() {
        val error = failure(wrapper("https://127.0.0.1:${closedPort()}/wrapped.xml"))

        assertEquals(301, error.vastError.code)
        assertTrue("cause: ${error.cause}", error.cause is IOException)
    }

    /** The test manifest's network security config forbids cleartext to `localhost`, and only to it. */
    @Test
    fun aWrapperTheCleartextPolicyRefusesFailsWithThePolicyError() {
        server.serve("/wrapped.xml", "application/xml", wrapper(address = null).toByteArray())

        val error = failure(wrapper(server.url("/wrapped.xml").replace("127.0.0.1", "localhost")))

        assertEquals(301, error.vastError.code)
        assertTrue("cause: ${error.cause}", error.cause is VastWrapperCleartextException)
        assertEquals("nothing reached the server", 0, server.requestCount("/wrapped.xml"))
    }

    @Test
    fun aWrapperChainDeeperThanFiveFailsAsTooDeep() {
        server.serve("/loop.xml", "application/xml", wrapper(server.url("/loop.xml")).toByteArray())

        val error = failure(wrapper(server.url("/loop.xml")))

        assertEquals(302, error.vastError.code)
        assertEquals("one fetch per level the parser follows", 5, server.requestCount("/loop.xml"))
    }

    @Test
    fun aWrapperLeadingToAResponseWithNoAdFailsAsAWrappedResponseWithNoAd() {
        server.serve("/empty.xml", "application/xml", "<VAST version=\"4.2\"></VAST>".toByteArray())

        val error = failure(wrapper(server.url("/empty.xml")))

        assertEquals(303, error.vastError.code)
    }

    @Test
    fun aDocumentThatIsNotVastIsAnInvalidDocument() {
        val error = failure("this is not a VAST document")

        assertEquals(100, error.vastError.code)
    }

    /** A local port nothing listens on, from a socket that was bound and then closed. */
    private fun closedPort(): Int = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
