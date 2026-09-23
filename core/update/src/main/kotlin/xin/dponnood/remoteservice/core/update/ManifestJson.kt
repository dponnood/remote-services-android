package xin.dponnood.remoteservice.core.update

/** Minimal strict JSON reader used so manifest validation is testable on the JVM. */
internal sealed interface ManifestJsonValue {
    data class StringValue(val value: String) : ManifestJsonValue
    data class NumberValue(val raw: String) : ManifestJsonValue
    data class BooleanValue(val value: Boolean) : ManifestJsonValue
    data object NullValue : ManifestJsonValue
    data class ObjectValue(val value: Map<String, ManifestJsonValue>) : ManifestJsonValue
    data class ArrayValue(val value: List<ManifestJsonValue>) : ManifestJsonValue
}

internal class ManifestJsonParseException(message: String) : IllegalArgumentException(message)

internal object ManifestJsonParser {
    fun parseObject(input: String): Map<String, ManifestJsonValue> =
        Reader(input).parseObject()

    private class Reader(private val input: String) {
        private var index = 0

        fun parseObject(): Map<String, ManifestJsonValue> {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != input.length) fail("trailing data")
            return (value as? ManifestJsonValue.ObjectValue)?.value ?: fail("root must be object")
        }

        private fun parseValue(): ManifestJsonValue {
            skipWhitespace()
            if (index >= input.length) fail("unexpected end")
            return when (input[index]) {
                '{' -> parseObjectValue()
                '[' -> parseArrayValue()
                '"' -> ManifestJsonValue.StringValue(parseString())
                't' -> parseLiteral("true", ManifestJsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", ManifestJsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", ManifestJsonValue.NullValue)
                '-', in '0'..'9' -> ManifestJsonValue.NumberValue(parseNumber())
                else -> fail("unexpected token at $index")
            }
        }

        private fun parseObjectValue(): ManifestJsonValue.ObjectValue {
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, ManifestJsonValue>()
            if (consume('}')) return ManifestJsonValue.ObjectValue(result)
            while (true) {
                skipWhitespace()
                if (index >= input.length || input[index] != '"') fail("object key must be string")
                val key = parseString()
                if (result.containsKey(key)) fail("duplicate object key")
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return ManifestJsonValue.ObjectValue(result)
        }

        private fun parseArrayValue(): ManifestJsonValue.ArrayValue {
            expect('[')
            skipWhitespace()
            val result = ArrayList<ManifestJsonValue>()
            if (consume(']')) return ManifestJsonValue.ArrayValue(result)
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return ManifestJsonValue.ArrayValue(result)
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (index >= input.length) fail("unfinished escape")
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicodeEscape())
                            else -> fail("invalid escape")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) fail("control character in string")
                        result.append(ch)
                    }
                }
            }
            fail("unterminated string")
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > input.length) fail("short unicode escape")
            val hex = input.substring(index, index + 4)
            if (!hex.matches(Regex("[0-9a-fA-F]{4}"))) fail("invalid unicode escape")
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): String {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < input.length && input[index].isDigit()) fail("leading zero")
            } else {
                if (!consumeDigits()) fail("invalid number")
            }
            if (consume('.')) {
                if (!consumeDigits()) fail("invalid fraction")
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                index++
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
                if (!consumeDigits()) fail("invalid exponent")
            }
            return input.substring(start, index)
        }

        private fun consumeDigits(): Boolean {
            val start = index
            while (index < input.length && input[index].isDigit()) index++
            return index > start
        }

        private fun <T> parseLiteral(literal: String, value: T): T where T : ManifestJsonValue {
            if (!input.startsWith(literal, index)) fail("invalid literal")
            index += literal.length
            return value
        }

        private fun consume(expected: Char): Boolean {
            if (index < input.length && input[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("expected '$expected'")
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index++
        }

        private fun fail(message: String): Nothing = throw ManifestJsonParseException(message)
    }
}
