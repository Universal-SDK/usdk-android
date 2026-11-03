package org.bidon.rendering.telemetry.catalogue

/**
 * The server's telemetry catalogue, read from its `events.proto`.
 *
 * The reader understands only the parts of proto3 the catalogue uses, and throws [UnsupportedProtoException] on
 * anything else, so a construct it does not know can never silently weaken the conformance check:
 * `syntax`, `package` and `import` lines; the `extend google.protobuf.MessageOptions` block that declares the
 * `event_name` option; top-level `enum` declarations; top-level messages with scalar fields (`string`, `bool`,
 * `int32`, `int64`, `double`), `repeated` scalars, `map<string, string>`, fields typed by another message or by an enum,
 * and the `option (event_name) = "..."` line. Comments are ignored.
 *
 * @property messages every message, by its type name
 * @property events every message that declares an `event_name` option, by that name; messages without one, such as
 *   `MetricObservation`, are not events and are left out
 */
internal class ProtoCatalogue(
    val messages: Map<String, Message>,
    val events: Map<String, Message>,
) {

    /** The `Envelope` message every event embeds. */
    val envelope: Message = messages["Envelope"] ?: throw UnsupportedProtoException("Unsupported proto: no `Envelope` message")

    /** The names a client may send: every event except the ones only the server emits. */
    val clientEventNames: Set<String> get() = events.keys - SERVER_ONLY_EVENTS

    class Message(val name: String, val fields: Map<String, FieldType>)

    sealed interface FieldType {
        data class Scalar(val kind: ScalarKind) : FieldType
        data class Repeated(val kind: ScalarKind) : FieldType
        data object StringMap : FieldType
        data class MessageRef(val name: String) : FieldType
        data class EnumRef(val name: String) : FieldType
    }

    enum class ScalarKind { STRING, BOOL, INT32, INT64, DOUBLE }

    class UnsupportedProtoException(message: String) : IllegalArgumentException(message)

    companion object {

        /** The auction and DSP events the server emits itself; the client ingest does not accept them. */
        val SERVER_ONLY_EVENTS = setOf(
            "auction_request_received",
            "auction_completed",
            "dsp_request_sent",
            "dsp_response_received",
            "dsp_response_rejected",
        )

        /** Reads [text], a whole `.proto` file. Throws [UnsupportedProtoException] on a construct outside the subset. */
        fun parse(text: String): ProtoCatalogue = Parser(tokenize(text)).parse()

        private val SCALARS = mapOf(
            "string" to ScalarKind.STRING,
            "bool" to ScalarKind.BOOL,
            "int32" to ScalarKind.INT32,
            "int64" to ScalarKind.INT64,
            "double" to ScalarKind.DOUBLE,
        )

        private val TOKEN = Regex("""\s+|//[^\n]*|/\*[\s\S]*?\*/|"[^"\n]*"|[A-Za-z_][A-Za-z0-9_.]*|-?\d+|[{}<>()=;,\[\]]|.""")

        /** The file's tokens, with whitespace and comments dropped. A character no rule matches is its own token. */
        private fun tokenize(text: String): List<String> = TOKEN.findAll(text).map { it.value }
            .filterNot { it.isBlank() || it.startsWith("//") || it.startsWith("/*") }
            .toList()
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0
        private val enums = mutableSetOf<String>()
        private val declared = mutableListOf<Declared>()

        /** A message as written: a field typed by a name is a [FieldType.MessageRef] until [resolve] looks it up. */
        private class Declared(val name: String, val eventName: String?, val fields: Map<String, FieldType>)

        fun parse(): ProtoCatalogue {
            while (pos < tokens.size) {
                when (val keyword = next()) {
                    "syntax" -> { expect("="); expect("\"proto3\""); expect(";") }
                    "package" -> { identifier(); expect(";") }
                    "import" -> { string(); expect(";") }
                    "extend" -> extend()
                    "enum" -> enum()
                    "message" -> message()
                    else -> unsupported("top-level `$keyword`")
                }
            }
            val names = declared.map { it.name }.toSet()
            val messages = declared.associate { it.name to Message(it.name, resolve(it, names)) }
            // The ingest skips a message whose event name is empty, so it is not an event here either.
            val events = declared.filter { !it.eventName.isNullOrEmpty() }
                .associate { it.eventName!! to messages.getValue(it.name) }
            return ProtoCatalogue(messages, events)
        }

        /** The `event_name` option: `extend google.protobuf.MessageOptions { string event_name = 50000; }`, no more. */
        private fun extend() {
            expect("google.protobuf.MessageOptions")
            expect("{")
            expect("string")
            expect("event_name")
            expect("=")
            number()
            expect(";")
            expect("}")
        }

        private fun enum() {
            enums += identifier()
            expect("{")
            while (peek() != "}") {
                identifier()
                expect("=")
                number()
                expect(";")
            }
            expect("}")
        }

        private fun message() {
            val name = identifier()
            var eventName: String? = null
            val fields = linkedMapOf<String, FieldType>()
            expect("{")
            while (peek() != "}") {
                when (val token = next()) {
                    "option" -> {
                        expect("(")
                        expect("event_name")
                        expect(")")
                        expect("=")
                        eventName = string()
                        expect(";")
                    }
                    "repeated" -> {
                        val type = identifier()
                        val kind = SCALARS[type] ?: unsupported("repeated `$type` in message $name")
                        field(FieldType.Repeated(kind), fields)
                    }
                    "map" -> {
                        expect("<")
                        expect("string")
                        expect(",")
                        expect("string")
                        expect(">")
                        field(FieldType.StringMap, fields)
                    }
                    "oneof", "message", "enum", "optional", "reserved", "extensions", "group", "required" ->
                        unsupported("`$token` in message $name")
                    else -> field(SCALARS[token]?.let(FieldType::Scalar) ?: FieldType.MessageRef(token), fields)
                }
            }
            expect("}")
            declared += Declared(name, eventName, fields)
        }

        private fun field(type: FieldType, into: MutableMap<String, FieldType>) {
            val name = identifier()
            expect("=")
            number()
            expect(";")
            into[name] = type
        }

        /** [message]'s fields with every type name resolved to an enum or a declared message. */
        private fun resolve(message: Declared, names: Set<String>): Map<String, FieldType> =
            message.fields.mapValues { (field, type) ->
                when {
                    type !is FieldType.MessageRef -> type
                    type.name in enums -> FieldType.EnumRef(type.name)
                    type.name in names -> type
                    else -> unsupported("type `${type.name}` of ${message.name}.$field")
                }
            }

        private fun peek(): String = tokens.getOrNull(pos) ?: unsupported("unexpected end of file")

        private fun next(): String = peek().also { pos++ }

        private fun expect(token: String) {
            val actual = next()
            if (actual != token) unsupported("expected `$token`, found `$actual`")
        }

        private fun identifier(): String = next().takeIf { it.first().isLetter() || it.first() == '_' }
            ?: unsupported("expected a name, found `${tokens[pos - 1]}`")

        private fun string(): String = next().takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
            ?.removeSurrounding("\"") ?: unsupported("expected a string, found `${tokens[pos - 1]}`")

        private fun number(): String = next().takeIf { it.toLongOrNull() != null }
            ?: unsupported("expected a number, found `${tokens[pos - 1]}`")

        private fun unsupported(what: String): Nothing = throw UnsupportedProtoException("Unsupported proto: $what")
    }
}
