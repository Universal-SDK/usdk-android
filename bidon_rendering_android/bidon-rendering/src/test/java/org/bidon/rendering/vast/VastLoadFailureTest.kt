package org.bidon.rendering.vast

import org.bidon.rendering.telemetry.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException

/** How a failed VAST load is reported (BID-136 AC 2 and AC 8): one code and one external pair for each kind. */
class VastLoadFailureTest {

    private fun parsing(code: Int, cause: Throwable? = null) = VastParsingException(VastError(code, "text"), cause)

    private fun vast(code: Int) = code.toString()

    @Test
    fun `an invalid document is 6004 with the VAST code 100`() {
        assertEquals(
            VastLoadFailure(ErrorCode.VAST_INVALID, vast(100)),
            VastLoadFailure.classify(parsing(100, IllegalStateException("a parser error"))),
        )
    }

    @Test
    fun `a wrapper chain that is too deep is 6005 with the VAST code 302`() {
        assertEquals(VastLoadFailure(ErrorCode.VAST_WRAPPER_TOO_DEEP, vast(302)), VastLoadFailure.classify(parsing(302)))
    }

    @Test
    fun `a wrapper without a usable address is 6006 with the VAST code 300`() {
        assertEquals(VastLoadFailure(ErrorCode.VAST_WRAPPER_NO_URI, vast(300)), VastLoadFailure.classify(parsing(300)))
    }

    @Test
    fun `a wrapped response with no ad that may play is 6014 with the VAST code 303`() {
        assertEquals(VastLoadFailure(ErrorCode.VAST_WRAPPED_NO_AD, vast(303)), VastLoadFailure.classify(parsing(303)))
    }

    @Test
    fun `a wrapper fetch nothing answered is 8001 with the VAST code 301`() {
        listOf<Throwable?>(ConnectException("refused"), SocketTimeoutException("slow"), IOException("reset"), null).forEach { cause ->
            assertEquals(
                "cause $cause",
                VastLoadFailure(ErrorCode.VAST_WRAPPER_NO_ANSWER, vast(301)),
                VastLoadFailure.classify(parsing(301, cause)),
            )
        }
    }

    @Test
    fun `a fetch that found no protocol both sides accept is a fetch that got no answer, not a cleartext refusal`() {
        assertEquals(
            VastLoadFailure(ErrorCode.VAST_WRAPPER_NO_ANSWER, vast(301)),
            VastLoadFailure.classify(parsing(301, UnknownServiceException("Unable to find acceptable protocols. isFallback=false"))),
        )
    }

    @Test
    fun `a VAST code the renderer does not know is an invalid document`() {
        listOf(0, 99, 101, 299, 304, 403, 900, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { code ->
            assertEquals(
                "VAST code $code",
                VastLoadFailure(ErrorCode.VAST_INVALID, vast(100)),
                VastLoadFailure.classify(parsing(code)),
            )
        }
    }

    @Test
    fun `an exception that is not a parsing failure is an invalid document`() {
        listOf<Throwable>(IllegalStateException("x"), IOException("y"), RuntimeException()).forEach {
            assertEquals(
                it.toString(),
                VastLoadFailure(ErrorCode.VAST_INVALID, vast(100)),
                VastLoadFailure.classify(it),
            )
        }
    }
}
