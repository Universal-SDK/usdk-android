package org.bidon.rendering.telemetry.catalogue

import java.security.MessageDigest
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.FieldType
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.ScalarKind
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.UnsupportedProtoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue reader: it fails on a construct outside the supported subset (BID-115 AC 8), reads the vendored
 * `events.proto` into exactly the 42 client event names (AC 9), and the vendored file is the one recorded next to it
 * (AC 10).
 *
 * To refresh the vendored catalogue, fetch the file at the new backend commit and record both together:
 * 1. `gh api "repos/bidon-io/bidon-backend/contents/schemas/proto/org/bidon/telemetry/v1/events.proto?ref=<commit>"
 *    --jq .content | base64 --decode > src/test/resources/telemetry/catalogue/events.proto`
 * 2. `shasum -a 256 src/test/resources/telemetry/catalogue/events.proto`
 * 3. Put the commit (and branch, if it changed) and the new hash into `events.proto.source` next to it.
 * 4. Update the expected event names below and the golden samples the new catalogue affects.
 */
class ProtoCatalogueTest {

    private fun proto(body: String) =
        "syntax = \"proto3\";\npackage test.v1;\nmessage Envelope { string event_id = 1; }\n\n$body\n"

    /** Parsing [body] fails, and the message names [construct], so it failed for that and nothing else. */
    private fun assertUnsupported(construct: String, body: String) {
        val error = assertThrows(UnsupportedProtoException::class.java) { ProtoCatalogue.parse(proto(body)) }
        assertTrue("expected `$construct` in: ${error.message}", error.message!!.contains("`$construct`"))
    }

    private val vendored get() = CatalogueTestSupport.vendoredCatalogue()

    @Test
    fun `a oneof is refused rather than skipped`() = assertUnsupported(
        "oneof",
        """
        message E {
          option (event_name) = "e";
          oneof choice { string a = 1; string b = 2; }
        }
        """.trimIndent(),
    )

    @Test
    fun `a nested message declaration is refused`() = assertUnsupported(
        "message",
        """
        message E {
          option (event_name) = "e";
          message Inner { string a = 1; }
          string b = 2;
        }
        """.trimIndent(),
    )

    @Test
    fun `a uint64 field is refused`() = assertUnsupported(
        "uint64",
        "message E {\n  option (event_name) = \"e\";\n  uint64 big = 1;\n}",
    )

    @Test
    fun `a bytes field is refused`() = assertUnsupported(
        "bytes",
        "message E {\n  option (event_name) = \"e\";\n  bytes blob = 1;\n}",
    )

    @Test
    fun `an optional field is refused`() = assertUnsupported(
        "optional",
        "message E {\n  option (event_name) = \"e\";\n  optional string a = 1;\n}",
    )

    @Test
    fun `an unknown top-level keyword is refused`() = assertUnsupported(
        "service",
        "service Ingest {\n  rpc Send (E) returns (E);\n}",
    )

    @Test
    fun `a message with an empty event name is not an event, as the ingest skips it`() {
        val catalogue = ProtoCatalogue.parse(
            proto("message E { option (event_name) = \"\"; Envelope envelope = 1; }"),
        )
        assertEquals(emptySet<String>(), catalogue.events.keys)
    }

    @Test
    fun `the vendored catalogue parses and yields exactly the 42 client event names`() {
        assertEquals(42, EXPECTED_CLIENT_EVENT_NAMES.size)
        assertEquals(EXPECTED_CLIENT_EVENT_NAMES, vendored.clientEventNames)
    }

    @Test
    fun `the vendored catalogue also holds the server-only events, which are not client names`() {
        assertTrue(vendored.events.keys.containsAll(ProtoCatalogue.SERVER_ONLY_EVENTS))
        assertEquals(47, vendored.events.size)
        assertTrue(vendored.clientEventNames.intersect(ProtoCatalogue.SERVER_ONLY_EVENTS).isEmpty())
    }

    @Test
    fun `MetricObservation has no event name and is not among the events`() {
        assertFalse(vendored.events.values.any { it.name == "MetricObservation" })
        assertFalse(vendored.events.containsKey("metric_observation"))
    }

    @Test
    fun `event_ts in the envelope is a 64-bit integer`() {
        assertEquals(FieldType.Scalar(ScalarKind.INT64), vendored.envelope.fields["event_ts"])
    }

    @Test
    fun `sampling_rate in the envelope is a double`() {
        assertEquals(FieldType.Scalar(ScalarKind.DOUBLE), vendored.envelope.fields["sampling_rate"])
    }

    @Test
    fun `adapters_registered is a repeated string`() {
        val field = vendored.events.getValue("sdk_init_completed").fields["adapters_registered"]
        assertEquals(FieldType.Repeated(ScalarKind.STRING), field)
    }

    @Test
    fun `an enum-typed field is recorded as an enum reference`() {
        val field = vendored.events.getValue("auction_completed").fields["scope"]
        assertEquals(FieldType.EnumRef("Scope"), field)
    }

    @Test
    fun `an event message embeds the envelope as a message field`() {
        val field = vendored.events.getValue("ad_load_failed").fields["envelope"]
        assertEquals(FieldType.MessageRef("Envelope"), field)
    }

    @Test
    fun `a map from string to string is read as a string map`() {
        val catalogue = ProtoCatalogue.parse(
            proto(
                """
                message E {
                  option (event_name) = "e";
                  map<string, string> labels = 2;
                }
                """.trimIndent(),
            ),
        )
        assertEquals(FieldType.StringMap, catalogue.events.getValue("e").fields["labels"])
    }

    @Test
    fun `line and block comments are ignored, even when they look like declarations`() {
        val catalogue = ProtoCatalogue.parse(
            proto(
                """
                // message Ghost { option (event_name) = "ghost"; }
                /* oneof hidden { string a = 1; }
                   message Phantom { option (event_name) = "phantom"; } */
                message E {
                  option (event_name) = "e"; // trailing comment with uint64
                  /* bytes inside = 9; */
                  string a = 1;
                }
                """.trimIndent(),
            ),
        )
        assertEquals(setOf("e"), catalogue.events.keys)
        assertEquals(setOf("a"), catalogue.events.getValue("e").fields.keys)
    }

    @Test
    fun `a message without an event_name option is not an event`() {
        val catalogue = ProtoCatalogue.parse(
            proto(
                """
                message Plain { string a = 1; }
                message E { option (event_name) = "e"; string b = 1; }
                """.trimIndent(),
            ),
        )
        assertEquals(setOf("e"), catalogue.events.keys)
    }

    @Test
    fun `the vendored proto hashes to the value recorded next to it`() {
        val recorded = CatalogueTestSupport.resourceText("telemetry/catalogue/events.proto.source")!!
            .lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val actual = MessageDigest.getInstance("SHA-256").digest(CatalogueTestSupport.vendoredProtoBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "events.proto was edited by hand or events.proto.source is stale; see the refresh steps in this class",
            recorded.getValue("sha256"),
            actual,
        )
    }

    private companion object {
        /** The 47 `event_name` options at the recorded commit, minus the five the server emits itself. */
        val EXPECTED_CLIENT_EVENT_NAMES = setOf(
            "sdk_init_started", "sdk_init_completed", "sdk_init_failed", "adapter_init_result",
            "config_fetch_result", "adapter_token_requested", "adapter_token_result",
            "token_collection_completed", "ad_request_started", "auction_requested",
            "auction_response_received", "ad_filled", "auction_failed", "auction_no_demand",
            "ad_load_requested", "adm_parse_result", "renderer_selected", "ad_loaded", "ad_load_failed",
            "ad_expired", "ad_show_requested", "ad_impression", "ad_show_failed", "ad_viewable",
            "ad_clicked", "ad_closed", "ad_reward_granted", "webview_error", "render_crashed",
            "video_start", "video_q1", "video_midpoint", "video_q3", "video_complete", "video_skipped",
            "video_error", "adapter_load_budget_exceeded", "no_fill_returned_to_max", "signal_requested",
            "signal_provided", "signal_failed", "signal_timeout_suspected",
        )
    }
}
