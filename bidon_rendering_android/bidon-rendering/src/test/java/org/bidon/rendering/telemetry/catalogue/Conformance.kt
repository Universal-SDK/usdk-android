package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.FieldType
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.Message
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.ScalarKind
import org.bidon.rendering.telemetry.keyNames
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Checks one encoded event against the catalogue, with the ingest's own rules plus a few stricter ones.
 *
 * Rules where the ingest drops the event or the field:
 * - [Rule.UNKNOWN_EVENT]: `envelope.event_name` is not declared by the catalogue; [Rule.SERVER_ONLY_EVENT]: it is one of
 *   [ProtoCatalogue.SERVER_ONLY_EVENTS]. With either, the event's other fields are not checked.
 * - [Rule.UNKNOWN_FIELD]: a top-level key that is not a field of the event's message, or a key under `envelope` that is
 *   not a field of `Envelope`.
 * - [Rule.MISSING_EVENT_ID]: `envelope.event_id` is absent or empty; [Rule.MISSING_EVENT_TS]: `envelope.event_ts` is
 *   absent. A present value of the wrong type is reported by the type rules.
 * - [Rule.WRONG_TYPE]: a value whose JSON type the field does not take. `string` takes a string, `bool` a boolean,
 *   `int32` and `int64` a number without a fraction, `double` any number, `repeated` an array of scalars of its type,
 *   `map<string, string>` an object of strings, a message field an object.
 * - [Rule.OUT_OF_RANGE]: an integer outside the range of its `int32` or `int64` field.
 * - [Rule.ENUM_FIELD]: any value in an enum-typed field, which no client value satisfies.
 *
 * Rules stricter than the ingest:
 * - [Rule.SERVER_STAMPED]: a field the server stamps (`schema_version`, `app_id`, `app_key`, `bundle`, `country`,
 *   `trace_id`) is sent under `envelope`.
 * - [Rule.INTEGER_AS_FLOAT]: an `int32` or `int64` value written with a fraction part or exponent that still makes a
 *   whole number (`5.0`, `1e3`), and `-0`, which org.json reads as a floating-point zero.
 * - [Rule.NULL_VALUE]: a value, or an element of a repeated field, is JSON `null`.
 *
 * A violation on a repeated or map field is reported at the field's key, not at the element.
 */
internal object Conformance {

    enum class Rule {
        UNKNOWN_EVENT,
        SERVER_ONLY_EVENT,
        UNKNOWN_FIELD,
        MISSING_EVENT_ID,
        MISSING_EVENT_TS,
        WRONG_TYPE,
        OUT_OF_RANGE,
        ENUM_FIELD,
        SERVER_STAMPED,
        INTEGER_AS_FLOAT,
        NULL_VALUE,
    }

    /**
     * One broken rule.
     *
     * @property event the event's `envelope.event_name`, or an empty string when there is none
     * @property key the dotted path of the offending key, such as `load_ms` or `envelope.event_ts`
     */
    data class Violation(val event: String, val key: String, val rule: Rule)

    private val SERVER_STAMPED_FIELDS = setOf("schema_version", "app_id", "app_key", "bundle", "country", "trace_id")

    /**
     * Every rule [eventJson], the text of one encoded event, breaks against [catalogue]; empty when it conforms. The
     * text is read as written, so `5` and `5.0` are told apart.
     */
    fun check(eventJson: String, catalogue: ProtoCatalogue): List<Violation> {
        val event = JSONObject(eventJson)
        val envelope = event.optJSONObject("envelope")
        val eventName = (envelope?.opt("event_name") as? String).orEmpty()
        return Check(eventName, catalogue).apply {
            val message = catalogue.events[eventName]
            when {
                message == null -> report("envelope.event_name", Rule.UNKNOWN_EVENT)
                eventName !in catalogue.clientEventNames -> report("envelope.event_name", Rule.SERVER_ONLY_EVENT)
                else -> {
                    checkObject(event, message, prefix = "")
                    val eventId = envelope?.opt("event_id")
                    if (eventId == null || eventId == "") report("envelope.event_id", Rule.MISSING_EVENT_ID)
                    if (envelope?.has("event_ts") != true) report("envelope.event_ts", Rule.MISSING_EVENT_TS)
                }
            }
        }.violations
    }

    /** The violations of one event, collected as its fields are walked. */
    private class Check(private val eventName: String, private val catalogue: ProtoCatalogue) {
        val violations = mutableListOf<Violation>()

        fun report(key: String, rule: Rule) {
            violations += Violation(eventName, key, rule)
        }

        fun checkObject(json: JSONObject, message: Message, prefix: String) {
            for (key in json.keyNames()) {
                val path = prefix + key
                val value = json.get(key)
                val type = message.fields[key]
                when {
                    value == JSONObject.NULL -> report(path, Rule.NULL_VALUE)
                    message === catalogue.envelope && key in SERVER_STAMPED_FIELDS -> report(path, Rule.SERVER_STAMPED)
                    type == null -> report(path, Rule.UNKNOWN_FIELD)
                    else -> checkValue(path, type, value)
                }
            }
        }

        private fun checkValue(path: String, type: FieldType, value: Any) {
            val rule = when (type) {
                is FieldType.Scalar -> scalarRule(type.kind, value)
                is FieldType.Repeated -> if (value is JSONArray) {
                    (0 until value.length()).firstNotNullOfOrNull { elementRule(type.kind, value.get(it)) }
                } else {
                    Rule.WRONG_TYPE
                }
                FieldType.StringMap ->
                    if (value is JSONObject && value.keyNames().all { value.get(it) is String }) null else Rule.WRONG_TYPE
                is FieldType.EnumRef -> Rule.ENUM_FIELD
                is FieldType.MessageRef -> if (value is JSONObject) {
                    checkObject(value, catalogue.messages.getValue(type.name), "$path.")
                    null
                } else {
                    Rule.WRONG_TYPE
                }
            }
            if (rule != null) report(path, rule)
        }
    }

    private fun elementRule(kind: ScalarKind, element: Any): Rule? =
        if (element == JSONObject.NULL) Rule.NULL_VALUE else scalarRule(kind, element)

    private fun scalarRule(kind: ScalarKind, value: Any): Rule? = when (kind) {
        ScalarKind.STRING -> if (value is String) null else Rule.WRONG_TYPE
        ScalarKind.BOOL -> if (value is Boolean) null else Rule.WRONG_TYPE
        ScalarKind.DOUBLE -> if (value is Number) null else Rule.WRONG_TYPE
        ScalarKind.INT32 -> integerRule(value, Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        ScalarKind.INT64 -> integerRule(value, Long.MIN_VALUE..Long.MAX_VALUE)
    }

    /**
     * The rule [value] breaks in an integer field holding [range], or `null` when it fits. org.json reads a number
     * written without a fraction or exponent as an `Integer`, `Long` or `BigInteger`, and any other as a `BigDecimal`
     * (a `Double` only for `-0`), so the written form survives parsing.
     */
    private fun integerRule(value: Any, range: LongRange): Rule? = when (value) {
        is Int -> if (value.toLong() in range) null else Rule.OUT_OF_RANGE
        is Long -> if (value in range) null else Rule.OUT_OF_RANGE
        is BigInteger -> Rule.OUT_OF_RANGE
        is BigDecimal -> if (value.stripTrailingZeros().scale() <= 0) Rule.INTEGER_AS_FLOAT else Rule.WRONG_TYPE
        is Double -> if (value == Math.rint(value)) Rule.INTEGER_AS_FLOAT else Rule.WRONG_TYPE
        else -> Rule.WRONG_TYPE
    }
}
