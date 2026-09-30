package org.bidon.rendering.telemetry

/**
 * Checks bytes against strict JSON (RFC 8259) as they are, without decoding them first.
 *
 * The platform's `org.json` is lenient: it reads comments, unquoted names, single quotes and more, and it reads text
 * decoded with replacement characters rather than the bytes that are sent. A server with a strict parser refuses all
 * of that, together with everything else in the same request. So a payload goes into a batch only if it passes here.
 */
internal object StrictJson {

    /** Deeper than any event is built, and shallow enough that checking never exhausts the thread's stack. */
    private const val MAX_DEPTH = 32

    /** Whether [bytes] are exactly one JSON object, with nothing around it but JSON whitespace, in valid UTF-8. */
    fun isOneObject(bytes: ByteArray): Boolean {
        val reader = Reader(bytes)
        return try {
            reader.skipWhitespace()
            if (reader.peek() != '{'.code) return false
            reader.value(depth = 0)
            reader.skipWhitespace()
            reader.atEnd()
        } catch (_: Malformed) {
            false
        }
    }

    /** Thrown to stop at the first byte that is not JSON. Shared and without a stack trace, as it carries nothing. */
    private object Malformed : RuntimeException(null, null, false, false)

    private class Reader(private val bytes: ByteArray) {

        private var position = 0

        fun atEnd(): Boolean = position == bytes.size

        /** The next byte as 0..255, or -1 at the end. */
        fun peek(): Int = if (position < bytes.size) bytes[position].toInt() and 0xFF else -1

        private fun next(): Int {
            val byte = peek()
            if (byte < 0) throw Malformed
            position++
            return byte
        }

        private fun expect(char: Char) {
            if (next() != char.code) throw Malformed
        }

        fun skipWhitespace() {
            while (true) {
                when (peek()) {
                    ' '.code, '\t'.code, '\n'.code, '\r'.code -> position++
                    else -> return
                }
            }
        }

        fun value(depth: Int) {
            if (depth > MAX_DEPTH) throw Malformed
            skipWhitespace()
            when (peek()) {
                '{'.code -> obj(depth)
                '['.code -> array(depth)
                '"'.code -> string()
                't'.code -> literal("true")
                'f'.code -> literal("false")
                'n'.code -> literal("null")
                else -> number()
            }
        }

        private fun obj(depth: Int) {
            expect('{')
            skipWhitespace()
            if (peek() == '}'.code) {
                position++
                return
            }
            while (true) {
                skipWhitespace()
                string()
                skipWhitespace()
                expect(':')
                value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ','.code -> continue
                    '}'.code -> return
                    else -> throw Malformed
                }
            }
        }

        private fun array(depth: Int) {
            expect('[')
            skipWhitespace()
            if (peek() == ']'.code) {
                position++
                return
            }
            while (true) {
                value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ','.code -> continue
                    ']'.code -> return
                    else -> throw Malformed
                }
            }
        }

        private fun string() {
            expect('"')
            while (true) {
                val byte = next()
                when {
                    byte == '"'.code -> return
                    byte == '\\'.code -> escape()
                    byte < 0x20 -> throw Malformed
                    byte >= 0x80 -> multiByte(byte)
                }
            }
        }

        private fun escape() {
            when (next()) {
                '"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> Unit
                'u'.code -> repeat(4) { if (!isHexDigit(next())) throw Malformed }
                else -> throw Malformed
            }
        }

        /**
         * The rest of a UTF-8 sequence started by [lead], as RFC 3629 allows it: no overlong forms, no encoded
         * surrogates, nothing past U+10FFFF.
         */
        private fun multiByte(lead: Int) {
            when (lead) {
                in 0xC2..0xDF -> continuation(0x80..0xBF)
                0xE0 -> continuation(0xA0..0xBF, more = 1)
                in 0xE1..0xEC, 0xEE, 0xEF -> continuation(0x80..0xBF, more = 1)
                0xED -> continuation(0x80..0x9F, more = 1)
                0xF0 -> continuation(0x90..0xBF, more = 2)
                in 0xF1..0xF3 -> continuation(0x80..0xBF, more = 2)
                0xF4 -> continuation(0x80..0x8F, more = 2)
                else -> throw Malformed
            }
        }

        /** One continuation byte in [first], then [more] in the ordinary range. */
        private fun continuation(first: IntRange, more: Int = 0) {
            if (next() !in first) throw Malformed
            repeat(more) { if (next() !in 0x80..0xBF) throw Malformed }
        }

        private fun number() {
            if (peek() == '-'.code) position++
            when (peek()) {
                '0'.code -> position++
                in '1'.code..'9'.code -> digits()
                else -> throw Malformed
            }
            if (peek() == '.'.code) {
                position++
                digits()
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                position++
                if (peek() == '+'.code || peek() == '-'.code) position++
                digits()
            }
        }

        /** One digit or more. */
        private fun digits() {
            if (peek() !in '0'.code..'9'.code) throw Malformed
            while (peek() in '0'.code..'9'.code) position++
        }

        private fun literal(word: String) {
            for (char in word) expect(char)
        }

        private fun isHexDigit(byte: Int): Boolean =
            byte in '0'.code..'9'.code || byte in 'a'.code..'f'.code || byte in 'A'.code..'F'.code
    }
}
