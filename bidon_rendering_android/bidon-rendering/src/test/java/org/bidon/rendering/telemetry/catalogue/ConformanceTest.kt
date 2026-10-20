package org.bidon.rendering.telemetry.catalogue

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.bidon.rendering.telemetry.catalogue.Conformance.Rule
import org.bidon.rendering.telemetry.catalogue.Conformance.Violation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conformance rules against the vendored catalogue: a valid event passes, and each way of breaking a rule is
 * reported with the event, the key and the rule (BID-115 AC 4 and AC 7).
 *
 * Assumption: a violation on a repeated or map field is reported at the field's key (`adapters_registered`), not at
 * an element inside it.
 */
class ConformanceTest {

    private val catalogue = CatalogueTestSupport.vendoredCatalogue()

    private fun envelope(
        name: String = "ad_load_failed",
        extra: String = "",
    ) = """"envelope":{"event_id":"2f1c9a64-3b1e-4d57-8a0e-5b7c1d2e9f10","event_name":"$name",""" +
        """"event_ts":1700000000000,"auction_id":"a","session_id":"s","ad_type":"banner","ad_format":"banner",""" +
        """"sampling_rate":1$extra}"""

    private fun check(json: String, against: ProtoCatalogue = catalogue) = Conformance.check(json, against)

    private fun fixture(name: String) =
        CatalogueTestSupport.resourceText("telemetry/conformance/$name.json")
            ?: error("missing negative fixture telemetry/conformance/$name.json")

    private fun assertOnly(expected: Violation, json: String, against: ProtoCatalogue = catalogue) =
        assertEquals(listOf(expected), check(json, against))

    private fun inline(body: String) = ProtoCatalogue.parse(
        "syntax = \"proto3\";\npackage test.v1;\n" +
            "message Envelope { string event_id = 1; string event_name = 2; int64 event_ts = 3; string auction_id = 6; string session_id = 7; string ad_type = 8; string ad_format = 9; double sampling_rate = 12; }\n$body",
    )

    @Test
    fun `a fully valid client event passes with no violations`() {
        val json = "{${envelope()},\"demand_id\":\"d\",\"error_domain\":\"render\",\"error_code\":\"timeout\"," +
            "\"duration_ms\":120,\"failed_stage\":\"load\"}"
        assertEquals(emptyList<Violation>(), check(json))
    }

    @Test
    fun `a misspelled field is reported as an unknown field`() = assertOnly(
        Violation("ad_load_failed", "duration_msx", Rule.UNKNOWN_FIELD),
        fixture("misspelled_field"),
    )

    @Test
    fun `an unknown event name is reported against the envelope event name`() = assertOnly(
        Violation("ad_load_faild", "envelope.event_name", Rule.UNKNOWN_EVENT),
        fixture("unknown_event_name"),
    )

    @Test
    fun `a string in an int64 field is a wrong type`() = assertOnly(
        Violation("ad_load_failed", "duration_ms", Rule.WRONG_TYPE),
        fixture("string_in_int64"),
    )

    @Test
    fun `a fraction in an int64 field is a wrong type`() = assertOnly(
        Violation("ad_load_failed", "duration_ms", Rule.WRONG_TYPE),
        fixture("fraction_in_int64"),
    )

    @Test
    fun `an array of objects in a repeated string field is a wrong type`() = assertOnly(
        Violation("sdk_init_completed", "adapters_registered", Rule.WRONG_TYPE),
        fixture("array_of_objects_in_repeated"),
    )

    @Test
    fun `event_ts as a string is a wrong type`() = assertOnly(
        Violation("ad_load_failed", "envelope.event_ts", Rule.WRONG_TYPE),
        fixture("event_ts_as_string"),
    )

    @Test
    fun `a missing event_id is reported`() = assertOnly(
        Violation("ad_load_failed", "envelope.event_id", Rule.MISSING_EVENT_ID),
        fixture("missing_event_id"),
    )

    @Test
    fun `a server-stamped field sent by the client is reported as server stamped, not as unknown`() = assertOnly(
        Violation("ad_load_failed", "envelope.schema_version", Rule.SERVER_STAMPED),
        fixture("server_stamped_field"),
    )

    @Test
    fun `a field set to null is reported as null and nothing else`() = assertOnly(
        Violation("ad_load_failed", "duration_ms", Rule.NULL_VALUE),
        fixture("null_value"),
    )

    @Test
    fun `an empty event_id is reported as missing`() = assertOnly(
        Violation("ad_load_failed", "envelope.event_id", Rule.MISSING_EVENT_ID),
        "{${envelope().replace("2f1c9a64-3b1e-4d57-8a0e-5b7c1d2e9f10", "")}}",
    )

    @Test
    fun `a server-only event name is reported as server only`() = assertOnly(
        Violation("auction_completed", "envelope.event_name", Rule.SERVER_ONLY_EVENT),
        "{${envelope("auction_completed")}}",
    )

    @Test
    fun `an unknown event reports only the event name, not its fields`() = assertOnly(
        Violation("no_such_event", "envelope.event_name", Rule.UNKNOWN_EVENT),
        "{${envelope("no_such_event")},\"bogus\":1,\"other\":\"x\"}",
    )

    @Test
    fun `an unknown key under the envelope is an unknown field`() = assertOnly(
        Violation("ad_load_failed", "envelope.mystery", Rule.UNKNOWN_FIELD),
        "{${envelope(extra = ",\"mystery\":1")}}",
    )

    @Test
    fun `an int32 value beyond the 32-bit range is out of range`() = assertOnly(
        Violation("config_fetch_result", "http_status", Rule.OUT_OF_RANGE),
        "{${envelope("config_fetch_result")},\"http_status\":2147483648}",
    )

    @Test
    fun `the largest int32 value is accepted`() {
        assertEquals(
            emptyList<Violation>(),
            check("{${envelope("config_fetch_result")},\"http_status\":2147483647}"),
        )
    }

    @Test
    fun `an int64 integer written as 5 point 0 is reported as an integer written as a float`() = assertOnly(
        Violation("ad_load_failed", "duration_ms", Rule.INTEGER_AS_FLOAT),
        "{${envelope()},\"duration_ms\":5.0}",
    )

    @Test
    fun `an int32 integer written as 5 point 0 is reported as an integer written as a float`() = assertOnly(
        Violation("config_fetch_result", "http_status", Rule.INTEGER_AS_FLOAT),
        "{${envelope("config_fetch_result")},\"http_status\":5.0}",
    )

    @Test
    fun `a double field accepts an integer`() {
        assertEquals(emptyList<Violation>(), check("{${envelope("ad_request_started")},\"price_floor\":3}"))
    }

    @Test
    fun `a double field accepts a fraction`() {
        assertEquals(emptyList<Violation>(), check("{${envelope("ad_request_started")},\"price_floor\":0.25}"))
    }

    @Test
    fun `a bool field rejects a string`() = assertOnly(
        Violation("config_fetch_result", "success", Rule.WRONG_TYPE),
        "{${envelope("config_fetch_result")},\"success\":\"true\"}",
    )

    @Test
    fun `a missing event_ts is reported`() = assertOnly(
        Violation("ad_load_failed", "envelope.event_ts", Rule.MISSING_EVENT_TS),
        "{${envelope().replace("\"event_ts\":1700000000000,", "")}}",
    )

    @Test
    fun `a string field rejects a number and a boolean`() {
        assertOnly(Violation("ad_load_failed", "demand_id", Rule.WRONG_TYPE), "{${envelope()},\"demand_id\":5}")
        assertOnly(Violation("ad_load_failed", "demand_id", Rule.WRONG_TYPE), "{${envelope()},\"demand_id\":true}")
    }

    @Test
    fun `a double field rejects a string`() = assertOnly(
        Violation("ad_request_started", "price_floor", Rule.WRONG_TYPE),
        "{${envelope("ad_request_started")},\"price_floor\":\"3\"}",
    )

    @Test
    fun `every field the server stamps is reported when the client sends it`() {
        listOf(
            "schema_version" to "\"1\"",
            "app_id" to "7",
            "app_key" to "\"k\"",
            "bundle" to "\"b\"",
            "country" to "\"PT\"",
            "trace_id" to "\"t\"",
        ).forEach { (key, value) ->
            assertOnly(
                Violation("ad_load_failed", "envelope.$key", Rule.SERVER_STAMPED),
                "{${envelope(extra = ",\"$key\":$value")}}",
            )
        }
    }

    @Test
    fun `a repeated field with a bad element after a good one is reported`() {
        assertOnly(
            Violation("sdk_init_completed", "adapters_registered", Rule.WRONG_TYPE),
            "{${envelope("sdk_init_completed")},\"adapters_registered\":[\"a\",1]}",
        )
        assertOnly(
            Violation("sdk_init_completed", "adapters_registered", Rule.NULL_VALUE),
            "{${envelope("sdk_init_completed")},\"adapters_registered\":[\"a\",null]}",
        )
    }

    @Test
    fun `any value in an enum-typed field is reported`() {
        val enumCatalogue = inline(
            """
            enum Kind { KIND_UNSPECIFIED = 0; KIND_A = 1; }
            message E { option (event_name) = "e"; Envelope envelope = 1; Kind kind = 2; }
            """.trimIndent(),
        )
        assertOnly(Violation("e", "kind", Rule.ENUM_FIELD), "{${envelope("e")},\"kind\":1}", enumCatalogue)
        assertOnly(Violation("e", "kind", Rule.ENUM_FIELD), "{${envelope("e")},\"kind\":\"KIND_A\"}", enumCatalogue)
    }

    @Test
    fun `a map field with a non-string value is a wrong type`() {
        val mapCatalogue = inline(
            "message E { option (event_name) = \"e\"; Envelope envelope = 1; map<string, string> labels = 2; }",
        )
        assertOnly(Violation("e", "labels", Rule.WRONG_TYPE), "{${envelope("e")},\"labels\":{\"a\":1}}", mapCatalogue)
        assertEquals(emptyList<Violation>(), check("{${envelope("e")},\"labels\":{\"a\":\"b\"}}", mapCatalogue))
    }

    @Test
    fun `a message field that is not an object is a wrong type`() {
        val nestedCatalogue = inline(
            """
            message Inner { string a = 1; }
            message E { option (event_name) = "e"; Envelope envelope = 1; Inner inner = 2; }
            """.trimIndent(),
        )
        assertOnly(Violation("e", "inner", Rule.WRONG_TYPE), "{${envelope("e")},\"inner\":\"x\"}", nestedCatalogue)
        assertEquals(emptyList<Violation>(), check("{${envelope("e")},\"inner\":{}}", nestedCatalogue))
    }

    @Test
    fun `every integer is accepted in an int64 field and its fractions are not`() {
        runBlocking {
        checkAll(Arb.long()) { value ->
            val json = "{${envelope()},\"duration_ms\":$value}"
            assertEquals(emptyList<Violation>(), check(json))
        }
        checkAll(Arb.double(-1e6, 1e6).filter { it % 1.0 != 0.0 }) { value ->
            val json = "{${envelope()},\"duration_ms\":${BigDecimal(value.toString()).toPlainString()}}"
            assertEquals(
                listOf(Violation("ad_load_failed", "duration_ms", Rule.WRONG_TYPE)),
                check(json),
            )
        }
        }
    }

    @Test
    fun `an int32 value is accepted exactly when it fits in 32 bits`() {
        runBlocking {
        checkAll(Arb.long()) { value ->
            val expected = if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                emptyList()
            } else {
                listOf(Violation("config_fetch_result", "http_status", Rule.OUT_OF_RANGE))
            }
            assertEquals(expected, check("{${envelope("config_fetch_result")},\"http_status\":$value}"))
        }
        }
    }
}
