package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [SettingsFile] against the acceptance criteria 15 (a corrupt or missing file reads as nothing and never throws)
 * and 18 (the written keys are exactly the allowed ones), plus the round trip and the temp-and-rename write.
 */
class SettingsFileTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val directory: File by lazy { temporary.newFolder() }
    private val path: File get() = File(directory, SettingsFile.FILE_NAME)
    private val settingsFile: SettingsFile get() = SettingsFile { path }

    private val endpointKeys = setOf("url", "app_key", "bundle", "sdk_version")
    private val switchKeys = setOf("enabled", "sample_rate", "event_rates", "event_names")

    private fun writtenKeys(): Set<String> = JSONObject(path.readText()).keyNames()

    @Test
    fun `full settings survive a round trip`() {
        settingsFile.write(fullSettings())

        assertEquals(fullSettings().view(), settingsFile.read().view())
    }

    @Test
    fun `a round trip works in a new instance, as in the next process`() {
        settingsFile.write(fullSettings())

        assertEquals(fullSettings().view(), SettingsFile { path }.read().view())
    }

    @Test
    fun `settings with only switches write only switch keys and read back with no endpoint`() {
        val config = TelemetryConfig(samplingRate = 0.4, eventRates = mapOf("a" to 0.1))

        settingsFile.write(TelemetrySettings(endpoint = null, config = config))

        assertTrue(writtenKeys().none { it in endpointKeys })
        assertTrue(switchKeys.containsAll(writtenKeys()))
        assertEquals(SettingsView(null, config), settingsFile.read().view())
    }

    @Test
    fun `settings with only an endpoint write only endpoint keys and read back with no switches`() {
        settingsFile.write(TelemetrySettings(testEndpoint(), config = null))

        assertEquals(endpointKeys, writtenKeys())
        assertEquals(SettingsView(testEndpoint().view(), null), settingsFile.read().view())
    }

    @Test
    fun `writing nothing reads back as nothing`() {
        settingsFile.write(TelemetrySettings.NONE)

        assertEquals(TelemetrySettings.NONE, settingsFile.read())
    }

    @Test
    fun `a write replaces everything stored before`() {
        settingsFile.write(fullSettings())

        settingsFile.write(TelemetrySettings(endpoint = null, config = TelemetryConfig(enabled = false)))

        assertEquals(SettingsView(null, TelemetryConfig(enabled = false)), settingsFile.read().view())
    }

    // AC 18

    @Test
    fun storedConfigHoldsOnlyEndpointAndSwitches() {
        settingsFile.write(fullSettings())

        assertEquals(endpointKeys + switchKeys, writtenKeys())
        assertEquals(
            setOf("url", "app_key", "bundle", "sdk_version", "enabled", "sample_rate", "event_rates", "event_names"),
            writtenKeys(),
        )
    }

    @Test
    fun `the written keys are never outside the allowed set, whatever is stored`() {
        val allowed = endpointKeys + switchKeys
        listOf(
            TelemetrySettings.NONE,
            TelemetrySettings(testEndpoint(), null),
            TelemetrySettings(null, TelemetryConfig.DEFAULT),
            TelemetrySettings(null, TelemetryConfig(allowedEvents = emptySet())),
            fullSettings(),
        ).forEach {
            settingsFile.write(it)

            assertTrue("keys ${writtenKeys()}", allowed.containsAll(writtenKeys()))
        }
    }

    // AC 15

    @Test
    fun `a missing file reads as nothing`() {
        assertFalse(path.exists())

        assertEquals(TelemetrySettings.NONE, settingsFile.read())
    }

    @Test
    fun corruptStoredConfig() {
        val corrupt = listOf(
            ByteArray(0),
            byteArrayOf(0, -1, -2, 127, 0, 1, 2, 3),
            "not json at all".toByteArray(),
            """{"url": "$TEST_URL", "app_key": "k", "bundle": "com.example.app", "sdk_ver""".toByteArray(),
            "[1, 2, 3]".toByteArray(),
            "null".toByteArray(),
            "\"text\"".toByteArray(),
            """{"url": 5, "app_key": 6, "bundle": 7, "sdk_version": 8, "enabled": "yes", "sample_rate": "x"}""".toByteArray(),
            """{"enabled": "yes"}""".toByteArray(),
            """{"sample_rate": 2.5}""".toByteArray(),
            """{"event_names": [1, 2]}""".toByteArray(),
            """{"event_rates": {"a": "b"}}""".toByteArray(),
            """{"url": "$TEST_URL"}""".toByteArray(),
        )

        corrupt.forEachIndexed { index, bytes ->
            path.writeBytes(bytes)

            assertEquals("case $index", TelemetrySettings.NONE, settingsFile.read())
        }
    }

    @Test
    fun `a corrupt file is overwritten by the next good write`() {
        path.writeBytes(byteArrayOf(0, -1, 5))

        settingsFile.write(fullSettings())

        assertEquals(fullSettings().view(), settingsFile.read().view())
    }

    @Test
    fun `the file is judged by the parser's rules, each part alone`() {
        path.writeText(
            """
            {"url": "$TEST_URL", "app_key": "k", "bundle": "$TEST_BUNDLE", "sdk_version": "$TEST_SDK_VERSION",
             "sample_rate": 7}
            """,
        )
        assertEquals(SettingsView(testEndpoint(appKey = "k").view(), null), settingsFile.read().view())

        path.writeText("""{"url": "not a url", "app_key": "k", "bundle": "b", "sdk_version": "1", "enabled": false}""")
        assertEquals(SettingsView(null, TelemetryConfig(enabled = false)), settingsFile.read().view())
    }

    @Test
    fun `a stored plain http url reads back with no endpoint`() {
        path.writeText(
            """
            {"url": "http://ingest.example.com/v2/telemetry", "app_key": "k", "bundle": "$TEST_BUNDLE",
             "sdk_version": "$TEST_SDK_VERSION", "enabled": false}
            """,
        )

        assertEquals(SettingsView(null, TelemetryConfig(enabled = false)), settingsFile.read().view())
    }

    @Test
    fun `unknown keys in the file are ignored`() {
        path.writeText(
            """
            {"url": "$TEST_URL", "app_key": "k", "bundle": "$TEST_BUNDLE", "sdk_version": "$TEST_SDK_VERSION",
             "advertising_id": "x", "extra": {"a": 1}}
            """,
        )

        assertEquals(SettingsView(testEndpoint(appKey = "k").view(), null), settingsFile.read().view())
    }

    // Temp file and rename

    @Test
    fun `a write leaves only the settings file behind`() {
        settingsFile.write(fullSettings())
        settingsFile.write(fullSettings(appKey = "second"))

        assertEquals(listOf(SettingsFile.FILE_NAME), directory.list()!!.toList())
        assertEquals("second", settingsFile.read().endpoint?.appKey)
    }

    @Test
    fun `a temporary file left by a crashed write does not break reading or writing`() {
        val leftovers = listOf("${SettingsFile.FILE_NAME}.tmp", "${SettingsFile.FILE_NAME}.temp", ".${SettingsFile.FILE_NAME}.tmp")
        leftovers.forEach { File(directory, it).writeText("""{"url": "http://half""") }

        assertEquals("only a leftover, no file", TelemetrySettings.NONE, settingsFile.read())

        settingsFile.write(fullSettings())
        assertEquals(fullSettings().view(), settingsFile.read().view())

        File(directory, "${SettingsFile.FILE_NAME}.tmp").writeText("garbage")
        assertEquals("a leftover next to a good file", fullSettings().view(), settingsFile.read().view())
    }

    @Test
    fun `a failed write throws IOException and leaves the old file whole`() {
        settingsFile.write(fullSettings())
        val missing = SettingsFile { File(directory, "missing/dir/${SettingsFile.FILE_NAME}") }

        try {
            missing.write(fullSettings())
            fail("expected IOException")
        } catch (_: IOException) {
        }

        assertNull(missing.read().endpoint)
        assertEquals(fullSettings().view(), settingsFile.read().view())
    }

    @Test
    fun `any valid settings survive a round trip`() = runBlocking<Unit> {
        val config = Arb.bind(
            Arb.boolean(),
            Arb.double(0.0, 1.0),
            Arb.list(Arb.string(0..6), 0..3),
            Arb.list(Arb.string(0..6), 0..3),
            Arb.boolean(),
        ) { enabled, rate, rateNames, names, withNames ->
            TelemetryConfig(
                enabled = enabled,
                samplingRate = rate,
                eventRates = rateNames.associateWith { rate / 2 },
                allowedEvents = if (withNames) names.toSet() else null,
            )
        }

        checkAll(config, Arb.boolean(), Arb.boolean()) { switches, withEndpoint, withConfig ->
            val settings = TelemetrySettings(
                endpoint = if (withEndpoint) testEndpoint() else null,
                config = if (withConfig) switches else null,
            )

            settingsFile.write(settings)

            assertEquals(settings.view(), settingsFile.read().view())
        }
    }

    @Test
    fun `a stored endpoint without its bundle or sdk version reads back with no endpoint`() {
        val full = JSONObject()
            .put("url", "https://ingest.example.com/v2/telemetry")
            .put("app_key", "key")
            .put("bundle", "com.example")
            .put("sdk_version", "1.0")
        listOf("bundle", "sdk_version").forEach { missing ->
            path.writeText(JSONObject(full.toString()).apply { remove(missing) }.toString())

            assertNull("without $missing", settingsFile.read().endpoint)
        }
    }
}
