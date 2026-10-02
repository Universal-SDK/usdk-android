package org.bidon.rendering.telemetry

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored payload goes into a batch only if it is exactly one JSON object under RFC 8259, read as the bytes that are
 * sent. Anything a lenient parser would forgive, a strict server can refuse, and it would take the whole batch with it.
 */
class StrictJsonTest {

    private fun accepts(text: String) = StrictJson.isOneObject(text.encodeToByteArray())

    private fun accepts(bytes: ByteArray) = StrictJson.isOneObject(bytes)

    @Test
    fun `one object, with any valid content and whitespace around it, is accepted`() {
        for (json in listOf(
            "{}",
            " \t\r\n{ } \n",
            """{"a":1,"b":-0,"c":1.5E-3,"d":1e10,"e":0.25,"f":-12}""",
            """{"a":true,"b":false,"c":null,"d":"","e":[],"f":{},"g":[1,"x",[{}],null]}""",
            """{"esc":"\" \\ \/ \b \f \n \r \t é 😀"}""",
            """{"utf8":"é ✓ 😀 ї"}""",
        )) {
            assertTrue(json, accepts(json))
        }
    }

    @Test
    fun `what lenient parsers forgive is refused`() {
        for (json in listOf(
            """{"n":1}//""", """{"n":1}/* x */""", """{"n":1}#""", """{/* x */"n":1}""",
            "{n:1}", "{'n':1}", """{"n":'x'}""", """{"n"=1}""", """{"n"=>1}""", """{"n":1;"m":2}""",
            """{"n":1,}""", """{"n":[1,]}""", """{,}""", """{"n":NaN}""", """{"n":Infinity}""", """{"n":0x1F}""",
            """{"n":01}""", """{"n":+1}""", """{"n":.5}""", """{"n":1.}""", """{"n":1e}""", """{"n":True}""",
            """{"n":"a	b"}""", """{"n":"\x41"}""", """{"n":"\u12"}""", """{"n":1}{"m":2}""", """{"n":1}]}""",
            // Whitespace to some parsers, not to JSON: vertical tab, form feed, no-break space.
            "\u000B{}", "{}\u000C", "{\"n\"\u000B:1}", "\u00A0{}",
        )) {
            assertFalse(json, accepts(json))
        }
    }

    @Test
    fun `anything but one object is refused`() {
        for (json in listOf("", " ", "[]", "[{}]", "\"x\"", "1", "null", "{", "}", "{\"n\":1")) {
            assertFalse(json, accepts(json))
        }
    }

    @Test
    fun `bytes a server could not read as text are refused`() {
        val cases = mapOf(
            "NUL after the object" to "{}".encodeToByteArray() + 0,
            "NUL inside a string" to byteArrayOf('{'.b, '"'.b, 'n'.b, 0, '"'.b, ':'.b, '1'.b, '}'.b),
            "byte order mark" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".encodeToByteArray(),
            "lone continuation byte" to string(0x80),
            "truncated sequence" to string(0xC3, 0x28),
            "overlong encoding" to string(0xC0, 0xAF),
            "overlong three bytes" to string(0xE0, 0x80, 0xAF),
            "encoded surrogate" to string(0xED, 0xA0, 0x80),
            "past U+10FFFF" to string(0xF4, 0x90, 0x80, 0x80),
            "invalid lead byte" to string(0xFF),
        )
        for ((name, bytes) in cases) {
            assertFalse(name, accepts(bytes))
        }
        assertTrue("the valid sequences around them", accepts(string(0xC3, 0xA9, 0xE2, 0x9C, 0x93, 0xF0, 0x9F, 0x98, 0x80)))
    }

    @Test
    fun `nesting is allowed 32 levels deep, the object itself included, and no deeper`() {
        fun nested(levels: Int) = "{\"a\":" + "[".repeat(levels - 1) + "]".repeat(levels - 1) + "}"

        assertTrue(accepts(nested(32)))
        assertFalse(accepts(nested(33)))
        assertFalse("without exhausting the stack", accepts(nested(100_000)))
    }

    @Test
    fun `escaped surrogates are accepted only as a pair`() {
        assertTrue(accepts("""{"s":"\uD83D\uDE00"}"""))
        assertTrue(accepts("""{"s":"\ud83d\ude00 and \u00e9"}"""))
        for (json in listOf(
            """{"s":"\uD800"}""",
            """{"s":"\uD800x"}""",
            """{"s":"\uD800\n"}""",
            """{"s":"\uD800\uD800"}""",
            """{"s":"\uD800\u0041"}""",
            """{"s":"\uDC00"}""",
            """{"s":"\uDE00\uD83D"}""",
        )) {
            assertFalse(json, accepts(json))
        }
    }

    @Test
    fun `every object org_json writes is accepted`() {
        runBlocking {
            checkAll(jsonObjects) { json -> assertTrue(json, accepts(json)) }
        }
    }

    @Test
    fun `no proper prefix of an object is accepted`() {
        runBlocking {
            checkAll(jsonObjects, Arb.int(0..1_000)) { json, cut ->
                val bytes = json.encodeToByteArray()
                assertFalse(json, accepts(bytes.copyOf(cut % bytes.size)))
            }
        }
    }

    private val Char.b: Byte get() = code.toByte()

    /** `{"s":"<bytes>"}`, so the bytes sit where only a string's content can be. */
    private fun string(vararg bytes: Int): ByteArray =
        "{\"s\":\"".encodeToByteArray() + ByteArray(bytes.size) { bytes[it].toByte() } + "\"}".encodeToByteArray()

    private companion object {
        /** Characters that must be escaped, that take several UTF-8 bytes, or both. */
        val text: Arb<String> = Arb.list(
            Arb.element(listOf("a", "Z", "0", " ", "\"", "\\", "/", "\n", "\t", "\u0001", "\u001F", "\u007F", "é", "ї", "✓", "\u2028", "😀")),
            0..12,
        ).map { it.joinToString("") }

        val scalars: Arb<Any> = Arb.choice(
            text,
            Arb.long(),
            Arb.double().filter { it.isFinite() },
            Arb.boolean(),
            Arb.int(0..0).map { JSONObject.NULL },
        )

        val flatArrays: Arb<Any> = Arb.list(scalars, 0..4).map { JSONArray(it) }

        val flatObjects: Arb<Any> = Arb.bind(Arb.list(text, 0..3), Arb.list(scalars, 3..3)) { keys, values ->
            JSONObject().apply { keys.forEachIndexed { index, key -> put(key, values[index]) } }
        }

        /** Arrays of scalars, arrays and objects: nesting three levels deep at most. */
        val arrays: Arb<Any> = Arb.list(Arb.choice(scalars, flatArrays, flatObjects), 0..4).map { JSONArray(it) }

        val jsonObjects: Arb<String> = Arb.bind(Arb.list(text, 0..6), Arb.list(Arb.choice(scalars, arrays, flatObjects), 6..6)) { keys, values ->
            JSONObject().apply { keys.forEachIndexed { index, key -> put(key, values[index]) } }.toString()
        }
    }
}
