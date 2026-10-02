package org.bidon.rendering.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryEndpointTest {

    private fun of(
        url: String = "https://sdkapi.example.com/v2/telemetry",
        appKey: String = "app_abc123",
        bundle: String = "com.example.game",
        sdkVersion: String = "0.14.0",
    ) = TelemetryEndpoint.of(url, appKey, bundle, sdkVersion)

    @Test
    fun `valid values make an endpoint that keeps them`() {
        val endpoint = of()!!

        assertEquals("https://sdkapi.example.com/v2/telemetry", endpoint.url.toString())
        assertEquals("app_abc123", endpoint.appKey)
        assertEquals("com.example.game", endpoint.bundle)
        assertEquals("0.14.0", endpoint.sdkVersion)
        assertNotNull(of(url = "http://10.0.2.2:1324/v2/telemetry"))
    }

    @Test
    fun `a URL that is not http or https makes no endpoint`() {
        for (url in listOf("", "sdkapi.example.com/v2/telemetry", "ftp://example.com/t", "file:///data/t", "javascript:x")) {
            assertNull(url, of(url = url))
        }
    }

    @Test
    fun `a blank or overlong value makes no endpoint`() {
        val long = "x".repeat(TelemetryEndpoint.MAX_VALUE_LENGTH + 1)
        assertNull(of(appKey = " "))
        assertNull(of(bundle = ""))
        assertNull(of(sdkVersion = ""))
        assertNull(of(appKey = long))
        assertNull(of(bundle = long))
        assertNull(of(sdkVersion = long))
        assertNotNull(of(appKey = "x".repeat(TelemetryEndpoint.MAX_VALUE_LENGTH)))
    }

    @Test
    fun `a version an HTTP header could not carry makes no endpoint`() {
        for (version in listOf("1.0\r\nX-Injected: 1", "1.0\n", "1.0\u0000", "версія")) {
            assertNull(version, of(sdkVersion = version))
        }
    }
}
