package io.github.hamzabhai.jhatpat.util

/**
 * A small JSON reader and string quoter. The app only talks to its own local server, so it needs
 * just objects, arrays, strings, numbers, booleans and null, and it must run in plain JVM unit
 * tests (where Android's org.json is not available).
 */
object Json {
    /**
     * Parses [text] into `Map<String, Any?>`, `List<Any?>`, `String`, `Long` (integers), `Double`,
     * `Boolean` or `null`. Throws [IllegalArgumentException] on malformed input.
     */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipSpace()
        val value = parser.readValue()
        parser.skipSpace()
        if (!parser.atEnd()) parser.fail("unexpected text after the value")
        return value
    }

    /** Returns [value] as a JSON string literal, quotes included. */
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000c' -> append("\\f")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length

        fun fail(message: String): Nothing = throw IllegalArgumentException("bad JSON at $pos: $message")

        fun skipSpace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else fail("unexpected end")

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            pos++
        }

        fun readValue(): Any? = when (val c = peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipSpace()
                expect(':')
                skipSpace()
                out[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipSpace()
                out += readValue()
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("short \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < s.length && s[pos] in "+-0123456789.eE") pos++
            val text = s.substring(start, pos)
            val isFloat = text.any { it in ".eE" }
            return (if (isFloat) text.toDoubleOrNull() else text.toLongOrNull()) ?: fail("bad number '$text'")
        }
    }
}
