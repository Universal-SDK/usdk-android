package org.bidon.rendering.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which wrapper addresses are fetched (BID-136 AC 8): any other is a wrapper without a usable URI, VAST 300. */
class WrapperUrlTest {

    @Test
    fun `an absolute http or https address with a host is fetched, ad server macros included`() {
        listOf(
            "http://ads.example/vast.xml",
            "https://ads.example/vast.xml?cb=[TIMESTAMP]&c=%%CACHEBUSTER%%",
            "http://127.0.0.1:8080/wrapped.xml",
        ).forEach { address -> assertEquals(address, wrapperUrlOrNull(address)?.toString()) }
    }

    @Test
    fun `an address that is blank, relative, not http or https, or has no host is not fetched`() {
        listOf(
            "",
            "not a url",
            "wrapped.xml",
            "ftp://ads.example/vast.xml",
            "file:///etc/hosts",
            "javascript:alert(1)",
            "http:///vast.xml",
            "https://:443/vast.xml",
        ).forEach { address -> assertNull("<$address>", wrapperUrlOrNull(address)) }
    }
}
