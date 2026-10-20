package org.bidon.rendering.telemetry.catalogue

import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.FieldType
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.Message
import org.bidon.rendering.telemetry.catalogue.ProtoCatalogue.ScalarKind
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Holds one encoded event against the catalogue, with the ingest's own rules plus a few stricter ones.
 *
 * Rules where the ingest drops the event or the field:
 * - [Rule.UNKNOWN_EVENT]: `envelope.event_name` is not declared by the catalogue; [Rule.SERVER_ONLY_EVENT]: it is one of
 *   [ProtoCatalogue.SERVER_ONLY_EVENTS]. With either, the event's other fields are not checked.
 * - [Rule.UNKNOWN_FIELD]: a top-level key that is not a field of the event's message, or a key under `envelope` that is
 *   not a field of `Envelope`.
 * - [Rule.MISSING_EVENT_ID]: `envelope.event_id` is absent or not a non-empty string.
 * - [Rule.WRONG_TYPE]: a value whose JSON type the field does not take. `string` takes a string, `bool` a boolean,
 *   `int32` and `int64` a number without a fraction, `double` any number, `repeated` an array of scalars of its type,
 *   `map<string, string>` an object of strings, a message field an object. `envelope.event_ts` that is not a JSON
 *   integer is reported here too.
 * - [Rule.OUT_OF_RANGE]: an `int32` value outside the 32-bit range.
 * - [Rule.ENUM_FIELD]: any value in an enum-typed field, which no client value satisfies.
 *
 * Rules stricter than the ingest:
 * - [Rule.SERVER_STAMPED]: a field the server stamps (`schema_version`, `app_id`, `app_key`, `bundle`, `country`,
 *   `trace_id`) is sent.
 * - [Rule.INTEGER_AS_FLOAT]: an `int32` or `int64` value written with a fraction part or exponent, even a zero one
 *   (`5.0`).
 * - [Rule.NULL_VALUE]: any value is JSON `null`.
 */
internal object Conformance {

    enum class Rule {
        UNKNOWN_EVENT,
        SERVER_ONLY_EVENT,
        UNKNOWN_FIELD,
        MISSING_EVENT_ID,
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

    /** The fields the server stamps on every event; a client never sends them. */
    val SERVER_STAMPED_FIELDS = setOf("schema_version", "app_id", "app_key", "bundle", "country", "trace_id")

    /**
     * Every rule [eventJson], the text of one encoded event, breaks against [catalogue]; empty when it conforms. The
     * text is read as written, so `5` and `5.0` are told apart.
     */
    fun check(eventJson: String, catalogue: ProtoCatalogue): List<Violation> {
        val event = JSONObject(eventJson)
        val eventName = (event.optJSONObject("envelope")?.opt("event_name") as? String).orEmpty()
        val message = catalogue.events[eventName]
        val violations = mutableListOf<Violation>()
        val report = { key: String, rule: Rule -> violations += Violation(eventName, key, rule) }
        when {
            message == null -> report("envelope.event_name", Rule.UNKNOWN_EVENT)
            eventName in ProtoCatalogue.SERVER_ONLY_EVENTS -> report("envelope.event_name", Rule.SERVER_ONLY_EVENT)
            else -> {
                checkObject(event, message, prefix = "", catalogue, report)
                val eventId = event.optJSONObject("envelope")?.opt("event_id")
                if (eventId == null || eventId == "") report("envelope.event_id", Rule.MISSING_EVENT_ID)
            }
        }
        return violations
    }

    private fun checkObject(
        json: JSONObject,
        message: Message,
        prefix: String,
        catalogue: ProtoCatalogue,
        report: (String, Rule) -> Unit,
    ) {
        for (key in json.keySet()) {
            val path = prefix + key
            val value = json.get(key)
            val type = message.fields[key]
            when {
                value == JSONObject.NULL -> report(path, Rule.NULL_VALUE)
                message === catalogue.envelope && key in SERVER_STAMPED_FIELDS -> report(path, Rule.SERVER_STAMPED)
                type == null -> report(path, Rule.UNKNOWN_FIELD)
                type is FieldType.MessageRef -> {
                    val nested = catalogue.messages.getValue(type.name)
                    if (value is JSONObject) checkObject(value, nested, "$path.", catalogue, report)
                    else report(path, Rule.WRONG_TYPE)
                }
                else -> typeRule(type, value)?.let { report(path, it) }
            }
        }
    }

    /** The rule [value] breaks as a value of [type], or `null` when it fits. */
    private fun typeRule(type: FieldType, value: Any): Rule? = when (type) {
        is FieldType.Scalar -> scalarRule(type.kind, value)
        is FieldType.Repeated ->
            if (value is JSONArray) (0 until value.length()).firstNotNullOfOrNull { scalarRule(type.kind, value.get(it)) }
            else Rule.WRONG_TYPE
        FieldType.StringMap ->
            if (value is JSONObject && value.keySet().all { value.get(it) is String }) null else Rule.WRONG_TYPE
        is FieldType.EnumRef -> Rule.ENUM_FIELD
        is FieldType.MessageRef -> error("checked by the caller")
    }

    private fun scalarRule(kind: ScalarKind, value: Any): Rule? = when (kind) {
        ScalarKind.STRING -> if (value is String) null else Rule.WRONG_TYPE
        ScalarKind.BOOL -> if (value is Boolean) null else Rule.WRONG_TYPE
        ScalarKind.DOUBLE -> if (value is Number) null else Rule.WRONG_TYPE
        ScalarKind.INT32 -> integerRule(value, Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        ScalarKind.INT64 -> integerRule(value, Long.MIN_VALUE..Long.MAX_VALUE)
    }

    /**
     * org.json reads a number written without a fraction or exponent as an `Integer`, `Long` or `BigInteger`, and any
     * other number as a `BigDecimal` or `Double`, so the written form survives parsing.
     */
    private fun integerRule(value: Any, range: LongRange): Rule? = when (value) {
        is Int, is Long -> if ((value as Number).toLong() in range) null else Rule.OUT_OF_RANGE
        is BigInteger -> Rule.OUT_OF_RANGE
        is BigDecimal -> if (value.signum() == 0 || value.stripTrailingZeros().scale() <= 0) Rule.INTEGER_AS_FLOAT else Rule.WRONG_TYPE
        is Double, is Float -> if ((value as Number).toDouble().let { it == Math.rint(it) && it.isFinite() }) Rule.INTEGER_AS_FLOAT else Rule.WRONG_TYPE
        else -> Rule.WRONG_TYPE
    }
}
