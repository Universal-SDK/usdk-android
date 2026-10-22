package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TelemetryBlock.parse] against the acceptance criteria 1 to 6: the endpoint part and the switches part are
 * judged alone, a malformed switch rejects the switches part whole, only https is accepted, and the app key falls back
 * when the block has none. Endpoints are compared by their values, since [TelemetryEndpoint] has no `equals`.
 */
class TelemetryBlockParserTest {

    private fun parse(
        block: String?,
        fallbackAppKey: String? = null,
        sdkVersion: String = TEST_SDK_VERSION,
        bundle: String = TEST_BUNDLE,
    ): TelemetrySettings = TelemetryBlock.parse(block?.let(::JSONObject), bundle, sdkVersion, fallbackAppKey)

    private fun parse(block: JSONObject, fallbackAppKey: String? = null) =
        TelemetryBlock.parse(block, TEST_BUNDLE, TEST_SDK_VERSION, fallbackAppKey)

    private fun switches(json: String): TelemetryConfig? = parse(json).config

    private fun block(vararg entries: Pair<String, Any>) = JSONObject().apply { entries.forEach { put(it.first, it.second) } }

    // AC 1

    @Test
    fun fullBlock() {
        val settings = parse(
            """
            {"url": "$TEST_URL", "app_key": "block-key", "enabled": false, "sample_rate": 0.25,
             "event_rates": {"ad_loaded": 0.05, "ad_shown": 0}, "event_names": ["ad_loaded", "ad_shown"]}
            """,
        )

        assertEquals(EndpointView(TEST_URL, "block-key", TEST_BUNDLE, TEST_SDK_VERSION), settings.endpoint?.view())
        assertEquals(FULL_CONFIG, settings.config)
    }

    // AC 2

    @Test
    fun missingBlock() {
        assertEquals(TelemetrySettings.NONE, parse(block = null))
        assertEquals(TelemetrySettings.NONE, parse(block = null, fallbackAppKey = "fallback"))
    }

    @Test
    fun emptyBlock() {
        val settings = parse("{}", fallbackAppKey = "fallback")

        assertNull(settings.endpoint)
        assertNull("absent switches are not the defaults", settings.config)
    }

    @Test
    fun blockOfWrongType() {
        // What the adapter hands over for a `telemetry` value that is not an object.
        val section = JSONObject("""{"telemetry": "https://ingest.example.com"}""")

        assertEquals(
            TelemetrySettings.NONE,
            TelemetryBlock.parse(section.optJSONObject("telemetry"), TEST_BUNDLE, TEST_SDK_VERSION, "fallback"),
        )
    }

    @Test
    fun `a block with no url and no switch gives nothing even with a key`() {
        val settings = parse("""{"app_key": "k", "other": 1}""")

        assertEquals(TelemetrySettings.NONE, settings)
    }

    // AC 3

    @Test
    fun unknownKeysIgnored() = runBlocking<Unit> {
        val known = setOf("url", "app_key", "enabled", "sample_rate", "event_rates", "event_names")
        val base = """
            {"url": "$TEST_URL", "app_key": "block-key", "enabled": false, "sample_rate": 0.25,
             "event_rates": {"ad_loaded": 0.05}, "event_names": ["ad_loaded"]}
        """
        val expected = parse(base).view()
        val extraValue: Arb<Any> = Arb.bind(Arb.int(0..4), Arb.string(0..6), Arb.int()) { kind, text, number ->
            when (kind) {
                0 -> text
                1 -> number
                2 -> number % 2 == 0
                3 -> JSONObject().put("nested", text)
                else -> JSONArray().put(text).put(number)
            }
        }
        val extras = Arb.list(Arb.bind(Arb.string(1..8), extraValue) { key, value -> key to value }, 1..6)

        checkAll(extras) { pairs ->
            val json = JSONObject(base)
            pairs.filter { it.first !in known }.forEach { json.put(it.first, it.second) }

            assertEquals(expected, parse(json).view())
        }
    }

    // AC 4

    @Test
    fun `a rate of 0 or 1 is accepted, as integer or decimal`() {
        assertEquals(0.0, switches("""{"sample_rate": 0}""")?.samplingRate)
        assertEquals(0.0, switches("""{"sample_rate": 0.0}""")?.samplingRate)
        assertEquals(1.0, switches("""{"sample_rate": 1}""")?.samplingRate)
        assertEquals(1.0, switches("""{"sample_rate": 1.0}""")?.samplingRate)
        assertEquals(mapOf("a" to 0.0, "b" to 1.0), switches("""{"event_rates": {"a": 0, "b": 1}}""")?.eventRates)
    }

    @Test
    fun `a rate that is not a number in 0 to 1 rejects the switches`() {
        val bad = listOf("-0.1", "1.5", "2", "-1", "\"0.5\"", "\"NaN\"", "true", "[0.5]", "{}", "1e999", "-1e999")

        bad.forEach { rate ->
            assertNull("sample_rate $rate", switches("""{"sample_rate": $rate}"""))
            assertNull("event rate $rate", switches("""{"event_rates": {"a": $rate}}"""))
        }
    }

    @Test
    fun `enabled must be a boolean`() {
        listOf("\"true\"", "\"false\"", "1", "0", "[]").forEach {
            assertNull("enabled $it", switches("""{"enabled": $it}"""))
        }
        assertEquals(false, switches("""{"enabled": false}""")?.enabled)
        assertEquals(true, switches("""{"enabled": true}""")?.enabled)
    }

    @Test
    fun `event_names must be an array of strings`() {
        listOf("""["a", 1]""", """["a", true]""", """["a", {}]""", "\"a\"", "5", "{}").forEach {
            assertNull("event_names $it", switches("""{"event_names": $it}"""))
        }
        assertEquals(setOf("a", "b"), switches("""{"event_names": ["a", "b", "a"]}""")?.allowedEvents)
    }

    @Test
    fun `an empty event_names array allows nothing, which is not the same as no list`() {
        assertEquals(emptySet<String>(), switches("""{"event_names": []}""")?.allowedEvents)
        assertNull(switches("""{"enabled": true}""")?.allowedEvents)
    }

    @Test
    fun `event_rates must be an object`() {
        listOf("[]", "[0.5]", "0.5", "\"a\"").forEach {
            assertNull("event_rates $it", switches("""{"event_rates": $it}"""))
        }
        assertEquals(emptyMap<String, Double>(), switches("""{"event_rates": {}}""")?.eventRates)
    }

    @Test
    fun `one bad switch rejects all of them, never half`() {
        assertNull(switches("""{"enabled": false, "sample_rate": 2, "event_names": ["a"]}"""))
        assertNull(switches("""{"enabled": false, "sample_rate": 0.5, "event_rates": {"a": -1}}"""))
        assertNull(switches("""{"enabled": false, "event_names": ["a", 1]}"""))
    }

    @Test
    fun `switches left out take the defaults, once at least one is present`() {
        assertEquals(TelemetryConfig(enabled = false), switches("""{"enabled": false}"""))
        assertEquals(TelemetryConfig(samplingRate = 0.5), switches("""{"sample_rate": 0.5}"""))
        assertEquals(TelemetryConfig(eventRates = mapOf("a" to 0.1)), switches("""{"event_rates": {"a": 0.1}}"""))
        assertEquals(TelemetryConfig(allowedEvents = setOf("a")), switches("""{"event_names": ["a"]}"""))
    }

    @Test
    fun `a bad switch does not cost a good endpoint`() {
        val settings = parse("""{"url": "$TEST_URL", "app_key": "k", "sample_rate": "high"}""")

        assertNull(settings.config)
        assertEquals(EndpointView(TEST_URL, "k", TEST_BUNDLE, TEST_SDK_VERSION), settings.endpoint?.view())
    }

    @Test
    fun `a bad endpoint does not cost good switches`() {
        val settings = parse("""{"url": "http://ingest.example.com", "app_key": "k", "enabled": false}""")

        assertNull(settings.endpoint)
        assertEquals(TelemetryConfig(enabled = false), settings.config)
    }

    @Test
    fun malformedSwitchesRejectedWhole() = runBlocking<Unit> {
        val expectedEndpoint = parse("""{"url": "$TEST_URL", "app_key": "k"}""").endpoint?.view()
        assertNotNull(expectedEndpoint)
        val malformed: Arb<Pair<String, Any>> = Arb.element(
            listOf(
                "sample_rate" to -0.1,
                "sample_rate" to 1.5,
                "sample_rate" to "0.5",
                "sample_rate" to true,
                "enabled" to "true",
                "enabled" to 1,
                "event_names" to JSONArray().put("a").put(1),
                "event_names" to "a",
                "event_rates" to JSONArray(),
                "event_rates" to JSONObject().put("a", 2.0),
                "event_rates" to JSONObject().put("a", "x"),
            ),
        )
        val outOfRange: Arb<Pair<String, Any>> = Arb.bind(Arb.boolean(), Arb.double(1.0001, 1.0e9), Arb.boolean()) { event, high, negative ->
            (if (event) "event_rates" else "sample_rate") to
                (if (event) JSONObject().put("x", if (negative) -high else high) else if (negative) -high else high)
        }
        val valid = Arb.bind(
            Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.boolean(),
            Arb.double(0.0, 1.0), Arb.list(Arb.string(1..5), 0..3),
        ) { withEnabled, enabled, withRate, withRates, withNames, rate, names ->
            JSONObject().apply {
                if (withEnabled) put("enabled", enabled)
                if (withRate) put("sample_rate", rate)
                if (withRates) put("event_rates", JSONObject().put("a", rate))
                if (withNames) put("event_names", JSONArray(names))
            }
        }

        checkAll(valid, Arb.element(listOf(0, 1)), malformed, outOfRange) { switchesPart, which, fixed, random ->
            val (key, value) = if (which == 0) fixed else random
            val json = switchesPart.put(key, value).put("url", TEST_URL).put("app_key", "k")

            val settings = parse(json)

            assertNull("config for $json", settings.config)
            assertEquals(expectedEndpoint, settings.endpoint?.view())
        }
    }

    // AC 5

    @Test
    fun plainHttpRefused() {
        assertNull(parse("""{"url": "http://ingest.example.com/v2/telemetry", "app_key": "k"}""").endpoint)
    }

    @Test
    fun `other schemes, missing and non-string urls give no endpoint`() {
        listOf(
            "\"ftp://ingest.example.com/v2/telemetry\"",
            "\"ingest.example.com/v2/telemetry\"",
            "\"not a url\"",
            "\"\"",
            "5",
            "true",
            "[]",
            "{}",
        ).forEach {
            assertNull("url $it", parse("""{"url": $it, "app_key": "k"}""").endpoint)
        }
        assertNull(parse("""{"app_key": "k", "enabled": true}""").endpoint)
    }

    @Test
    fun nonHttpsKeepsStoredEndpoint() {
        val stored = TelemetrySettings(testEndpoint(), TelemetryConfig(samplingRate = 0.5))

        val merged = parse("""{"url": "http://other.example.com", "app_key": "k"}""").over(stored)

        assertEquals(stored.view(), merged.view())
    }

    @Test
    fun `a blank or over-long app key, a non-ASCII sdk version or a blank bundle gives no endpoint`() {
        assertNull(parse("""{"url": "$TEST_URL", "app_key": "   "}""").endpoint)
        assertNull(parse("""{"url": "$TEST_URL", "app_key": "${"k".repeat(257)}"}""").endpoint)
        assertNotNull(parse("""{"url": "$TEST_URL", "app_key": "${"k".repeat(256)}"}""").endpoint)
        assertNull(parse("""{"url": "$TEST_URL", "app_key": "k"}""", sdkVersion = "1.0.é").endpoint)
        assertNull(parse("""{"url": "$TEST_URL", "app_key": "k"}""", sdkVersion = " ").endpoint)
        assertNull(parse("""{"url": "$TEST_URL", "app_key": "k"}""", bundle = "").endpoint)
    }

    // AC 6

    @Test
    fun fallbackKeyUsedWhenBlockHasNone() {
        val settings = parse("""{"url": "$TEST_URL"}""", fallbackAppKey = "from-preferences")

        assertEquals(EndpointView(TEST_URL, "from-preferences", TEST_BUNDLE, TEST_SDK_VERSION), settings.endpoint?.view())
    }

    @Test
    fun `a blank or non-string block key counts as none and the fallback is used`() {
        listOf("\"\"", "\"   \"", "5", "true", "{}").forEach {
            val settings = parse("""{"url": "$TEST_URL", "app_key": $it}""", fallbackAppKey = "from-preferences")

            assertEquals("app_key $it", "from-preferences", settings.endpoint?.appKey)
        }
    }

    @Test
    fun blockKeyWinsOverFallback() {
        val settings = parse("""{"url": "$TEST_URL", "app_key": "block-key"}""", fallbackAppKey = "from-preferences")

        assertEquals("block-key", settings.endpoint?.appKey)
    }

    @Test
    fun noAppKeyAnywhere() {
        listOf(null, "", "   ").forEach { fallback ->
            val settings = parse("""{"url": "$TEST_URL", "enabled": false}""", fallbackAppKey = fallback)

            assertNull("fallback '$fallback'", settings.endpoint)
            assertEquals(TelemetryConfig(enabled = false), settings.config)
        }
    }

    @Test
    fun `a block key that is present but too long does not fall back`() {
        val settings = parse("""{"url": "$TEST_URL", "app_key": "${"k".repeat(257)}"}""", fallbackAppKey = "fallback")

        assertNull(settings.endpoint)
    }

    // The two building blocks

    @Test
    fun `endpoint and switches can be asked for directly`() {
        assertNull(TelemetryBlock.endpoint(null, "k", TEST_BUNDLE, TEST_SDK_VERSION))
        assertNull(TelemetryBlock.endpoint(TEST_URL, null, TEST_BUNDLE, TEST_SDK_VERSION))
        assertNull(TelemetryBlock.endpoint("http://ingest.example.com", "k", TEST_BUNDLE, TEST_SDK_VERSION))
        assertEquals(
            EndpointView(TEST_URL, "k", TEST_BUNDLE, TEST_SDK_VERSION),
            TelemetryBlock.endpoint(TEST_URL, "k", TEST_BUNDLE, TEST_SDK_VERSION)?.view(),
        )
        assertNull(TelemetryBlock.switches(JSONObject()))
        assertEquals(TelemetryConfig(enabled = false), TelemetryBlock.switches(block("enabled" to false)))
    }
}
