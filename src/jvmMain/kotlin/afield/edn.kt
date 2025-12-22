package afield

import java.math.BigDecimal
import kotlin.reflect.KClass

// EDN AST
sealed class EdnValue {
    object Nil : EdnValue()
    data class Bool(val value: Boolean) : EdnValue()
    data class Number(val token: String) : EdnValue()
    data class Str(val value: String) : EdnValue()
    data class ListVal(val values: List<EdnValue>) : EdnValue()
    data class VectorVal(val values: List<EdnValue>) : EdnValue()
    data class SetVal(val values: Set<EdnValue>) : EdnValue()
    data class MapVal(val values: Map<EdnValue, EdnValue>) : EdnValue()
    data class Symbol(val name: String) : EdnValue()
    data class Keyword(val name: String) : EdnValue()
    data class Tagged(val tag: String, val value: EdnValue) : EdnValue()
}

// Public EDN facade
object Edn {

    /**
     * Strategy for mapping EDN numeric tokens to JVM numeric types.
     *
     * Default strategy:
     * - integers -> Int if in range, else Long, else BigDecimal
     * - decimals / scientific notation -> Double
     */
    interface NumberStrategy {
        fun fromToken(token: String): Any
    }

    object DefaultNumberStrategy : NumberStrategy {
        override fun fromToken(token: String): Any {
            val isFloating =
                token.contains('.') || token.contains('e') || token.contains('E')

            return if (isFloating) {
                token.toDouble()
            } else {
                try {
                    val longVal = token.toLong()
                    if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) {
                        longVal.toInt()
                    } else {
                        longVal
                    }
                } catch (e: NumberFormatException) {
                    BigDecimal(token)
                }
            }
        }
    }

    /**
     * Global default strategy used when you don't pass a NumberStrategy explicitly.
     */
    var defaultNumberStrategy: NumberStrategy = DefaultNumberStrategy

    /**
     * Registry for custom tagged types.
     *
     * You can either:
     *  - use [register] / [registerStringPayload] to encode as `#tag "string"`
     *  - use [registerEdn] to encode as `#tag <any-edn-value>`
     */
    class CustomTypes {

        private data class Encoder(
            val tag: String,
            val encode: (Any) -> EdnValue
        )

        private val encoders = LinkedHashMap<KClass<*>, Encoder>()
        private val decoders = LinkedHashMap<String, (EdnValue) -> Any>()

        /**
         * Convenience: encode the custom type as `#tag "string"`.
         */
        fun <T : Any> register(
            type: KClass<T>,
            tag: String,
            encode: (T) -> String,
            decode: (String) -> T
        ): CustomTypes = registerStringPayload(type, tag, encode, decode)

        fun <T : Any> registerStringPayload(
            type: KClass<T>,
            tag: String,
            encode: (T) -> String,
            decode: (String) -> T
        ): CustomTypes {
            encoders[type] = Encoder(tag) { any ->
                @Suppress("UNCHECKED_CAST")
                EdnValue.Str(encode(any as T))
            }
            decoders[tag] = { ednValue ->
                val str = ednValue as? EdnValue.Str
                    ?: throw IllegalArgumentException(
                        "Expected EDN string as payload for tag '$tag'"
                    )
                decode(str.value)
            }
            return this
        }

        /**
         * Advanced: encode as arbitrary EDN, e.g. `#tag [x y z]`.
         */
        fun <T : Any> registerEdn(
            type: KClass<T>,
            tag: String,
            encode: (T) -> EdnValue,
            decode: (EdnValue) -> T
        ): CustomTypes {
            encoders[type] = Encoder(tag) { any ->
                @Suppress("UNCHECKED_CAST")
                encode(any as T)
            }
            decoders[tag] = { ednValue -> decode(ednValue) }
            return this
        }

        internal fun tryEncode(value: Any): EdnValue.Tagged? {
            for ((kClass, encoder) in encoders) {
                if (kClass.isInstance(value)) {
                    val payload = encoder.encode(value)
                    return EdnValue.Tagged(encoder.tag, payload)
                }
            }
            return null
        }

        internal fun tryDecode(tag: String, value: EdnValue): Any? {
            val decoder = decoders[tag] ?: return null
            return decoder(value)
        }
    }

    // --- Public API ---

    /** Serialize a single Kotlin value to an EDN string. */
    fun write(value: Any?, custom: CustomTypes? = null): String {
        val ednValue = toEdnValue(value, custom)
        return EdnPrinter.print(ednValue)
    }

    /** Serialize a list of values as an EDN vector. */
    fun writeList(values: List<Any?>, custom: CustomTypes? = null): String {
        val ednVector = EdnValue.VectorVal(values.map { toEdnValue(it, custom) })
        return EdnPrinter.print(ednVector)
    }

    /** Deserialize an EDN string into Kotlin values using the given strategies. */
    fun read(
        edn: String,
        custom: CustomTypes? = null,
        numberStrategy: NumberStrategy = defaultNumberStrategy
    ): Any? {
        val ednValue = EdnParser(edn).parse()
        return fromEdnValue(ednValue, custom, numberStrategy)
    }

    /** Deserialize a top-level EDN vector or list into `List<Any?>`. */
    fun readList(
        edn: String,
        custom: CustomTypes? = null,
        numberStrategy: NumberStrategy = defaultNumberStrategy
    ): List<Any?> {
        val root = EdnParser(edn).parse()
        val elements: List<EdnValue> = when (root) {
            is EdnValue.VectorVal -> root.values
            is EdnValue.ListVal -> root.values
            else -> throw IllegalArgumentException(
                "Expected EDN vector or list at root, got: $root"
            )
        }
        return elements.map { fromEdnValue(it, custom, numberStrategy) }
    }

    // --- Kotlin <-> EdnValue conversion ---

    private fun toEdnValue(value: Any?, custom: CustomTypes?): EdnValue {
        if (value == null) return EdnValue.Nil

        // Custom tagged types first
        if (custom != null) {
            val tagged = custom.tryEncode(value)
            if (tagged != null) return tagged
        }

        return when (value) {
            is Boolean -> EdnValue.Bool(value)

            is Int, is Long, is Short, is Byte ->
                EdnValue.Number(value.toString())

            is Float, is Double ->
                EdnValue.Number(value.toString())

            is BigDecimal ->
                EdnValue.Number(value.toPlainString())

            is String -> EdnValue.Str(value)

            is List<*> -> {
                val listValues = value.map { toEdnValue(it, custom) }
                EdnValue.VectorVal(listValues)
            }

            is Set<*> -> {
                val setValues = value
                    .map { toEdnValue(it, custom) }
                    .toCollection(LinkedHashSet())
                EdnValue.SetVal(setValues)
            }

            is Map<*, *> -> {
                val mapValues = LinkedHashMap<EdnValue, EdnValue>()
                for ((k, v) in value) {
                    val keyEdn = toEdnValue(k, custom)
                    val valEdn = toEdnValue(v, custom)
                    mapValues[keyEdn] = valEdn
                }
                EdnValue.MapVal(mapValues)
            }

            else -> throw IllegalArgumentException(
                "Don't know how to EDN-encode type ${value::class}"
            )
        }
    }

    private fun fromEdnValue(
        value: EdnValue,
        custom: CustomTypes?,
        numberStrategy: NumberStrategy
    ): Any? {
        if (value is EdnValue.Tagged) {
            if (custom != null) {
                val decoded = custom.tryDecode(value.tag, value.value)
                if (decoded != null) return decoded
            }
            // Unknown tag -> preserve EDN form
            return value
        }

        return when (value) {
            EdnValue.Nil -> null
            is EdnValue.Bool -> value.value
            is EdnValue.Number -> numberStrategy.fromToken(value.token)
            is EdnValue.Str -> value.value

            is EdnValue.VectorVal ->
                value.values.map { fromEdnValue(it, custom, numberStrategy) }

            is EdnValue.ListVal ->
                value.values.map { fromEdnValue(it, custom, numberStrategy) }

            is EdnValue.SetVal ->
                value.values.mapTo(LinkedHashSet()) {
                    fromEdnValue(it, custom, numberStrategy)
                }

            is EdnValue.MapVal -> {
                val result = LinkedHashMap<Any?, Any?>()
                for ((k, v) in value.values) {
                    result[fromEdnValue(k, custom, numberStrategy)] =
                        fromEdnValue(v, custom, numberStrategy)
                }
                result
            }

            is EdnValue.Symbol -> value.name
            is EdnValue.Keyword -> ":" + value.name
            is EdnValue.Tagged ->
                throw IllegalStateException("Tagged value should have been handled earlier")
        }
    }
}

// --- EDN parser ---
private class EdnParser(private val src: String) {
    private var index: Int = 0

    fun parse(): EdnValue {
        skipWhitespaceAndComments()
        val value = parseValue()
        skipWhitespaceAndComments()
        if (index != src.length) {
            throw IllegalArgumentException("Unexpected extra input at position $index")
        }
        return value
    }

    private fun eof(): Boolean = index >= src.length

    private fun currentChar(): Char =
        if (!eof()) src[index] else throw IllegalStateException("Unexpected EOF")

    private fun peek(offset: Int): Char? {
        val i = index + offset
        return if (i in src.indices) src[i] else null
    }

    private fun consume(): Char {
        val c = currentChar()
        index++
        return c
    }

    private fun expectChar(expected: Char) {
        val c: Char? = if (!eof()) src[index] else null
        if (c != expected) {
            throw IllegalArgumentException("Expected '$expected' at $index, got '$c'")
        }
        index++
    }

    private fun skipWhitespaceAndComments() {
        while (!eof()) {
            val c = src[index]
            when {
                c == ';' -> {
                    // Comment until end of line
                    index++
                    while (!eof() && src[index] != '\n' && src[index] != '\r') {
                        index++
                    }
                }

                c == ',' || c.isWhitespace() -> index++
                else -> return
            }
        }
    }

    private fun parseValue(): EdnValue {
        skipWhitespaceAndComments()
        if (eof()) throw IllegalArgumentException("Unexpected EOF while reading EDN")

        return when (currentChar()) {
            '(' -> parseList()
            '[' -> parseVector()
            '{' -> parseMap()
            '"' -> parseString()
            ':' -> parseKeyword()
            '#' -> {
                when (peek(1)) {
                    '{' -> parseSet()
                    else -> parseTagged()
                }
            }

            else -> parseTokenValue()
        }
    }

    private fun parseList(): EdnValue {
        expectChar('(')
        val values = mutableListOf<EdnValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (eof()) throw IllegalArgumentException("Unterminated list")
            if (currentChar() == ')') {
                index++
                break
            }
            values += parseValue()
        }
        return EdnValue.ListVal(values)
    }

    private fun parseVector(): EdnValue {
        expectChar('[')
        val values = mutableListOf<EdnValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (eof()) throw IllegalArgumentException("Unterminated vector")
            if (currentChar() == ']') {
                index++
                break
            }
            values += parseValue()
        }
        return EdnValue.VectorVal(values)
    }

    private fun parseMap(): EdnValue {
        expectChar('{')
        val values = LinkedHashMap<EdnValue, EdnValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (eof()) throw IllegalArgumentException("Unterminated map")
            if (currentChar() == '}') {
                index++
                break
            }
            val key = parseValue()
            skipWhitespaceAndComments()
            if (eof()) throw IllegalArgumentException("Map with odd number of forms")
            val value = parseValue()
            values[key] = value
        }
        return EdnValue.MapVal(values)
    }

    private fun parseSet(): EdnValue {
        expectChar('#')
        expectChar('{')
        val values = LinkedHashSet<EdnValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (eof()) throw IllegalArgumentException("Unterminated set")
            if (currentChar() == '}') {
                index++
                break
            }
            values += parseValue()
        }
        return EdnValue.SetVal(values)
    }

    private fun parseTagged(): EdnValue {
        expectChar('#')
        skipWhitespaceAndComments()
        val tagToken = readToken()
        skipWhitespaceAndComments()
        val value = parseValue()
        return EdnValue.Tagged(tagToken, value)
    }

    private fun parseKeyword(): EdnValue {
        expectChar(':')
        val start = index
        while (!eof() && !isTerminating(src[index]) && !src[index].isWhitespace()) {
            index++
        }
        if (start == index) {
            throw IllegalArgumentException("Empty keyword at $index")
        }
        val name = src.substring(start, index)
        return EdnValue.Keyword(name)
    }

    private fun parseString(): EdnValue {
        expectChar('"')
        val sb = StringBuilder()
        while (!eof()) {
            val c = consume()
            if (c == '"') {
                return EdnValue.Str(sb.toString())
            }
            if (c == '\\') {
                if (eof()) throw IllegalArgumentException("Bad escape at end of input")
                val esc = consume()
                when (esc) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (index + 4 > src.length) {
                            throw IllegalArgumentException("Invalid unicode escape at $index")
                        }
                        val hex = src.substring(index, index + 4)
                        val codePoint = hex.toInt(16)
                        sb.append(codePoint.toChar())
                        index += 4
                    }

                    else -> throw IllegalArgumentException("Unknown escape sequence: \\$esc")
                }
            } else {
                sb.append(c)
            }
        }
        throw IllegalArgumentException("Unterminated string literal")
    }

    private fun parseTokenValue(): EdnValue {
        val token = readToken()
        return when {
            token == "nil" -> EdnValue.Nil
            token == "true" -> EdnValue.Bool(true)
            token == "false" -> EdnValue.Bool(false)
            NUMBER_REGEX.matches(token) -> EdnValue.Number(token)
            else -> EdnValue.Symbol(token)
        }
    }

    private fun readToken(): String {
        skipWhitespaceAndComments()
        if (eof()) throw IllegalArgumentException("Expected token, found EOF")
        val start = index
        while (!eof() && !isTerminating(src[index]) && !src[index].isWhitespace()) {
            index++
        }
        if (start == index) {
            throw IllegalArgumentException("Empty token at $index")
        }
        return src.substring(start, index)
    }

    private fun isTerminating(c: Char): Boolean = when (c) {
        '(', ')', '[', ']', '{', '}', '"', ';', '#', ',' -> true
        else -> false
    }

    companion object {
        private val NUMBER_REGEX =
            Regex("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")
    }
}

// --- EDN printer ---
private object EdnPrinter {

    fun print(value: EdnValue): String {
        val sb = StringBuilder()
        append(value, sb)
        return sb.toString()
    }

    private fun append(value: EdnValue, out: StringBuilder) {
        when (value) {
            EdnValue.Nil -> out.append("nil")
            is EdnValue.Bool -> out.append(if (value.value) "true" else "false")
            is EdnValue.Number -> out.append(value.token)
            is EdnValue.Str -> appendString(value.value, out)

            is EdnValue.ListVal -> {
                out.append('(')
                appendSequence(value.values, out)
                out.append(')')
            }

            is EdnValue.VectorVal -> {
                out.append('[')
                appendSequence(value.values, out)
                out.append(']')
            }

            is EdnValue.SetVal -> {
                out.append("#{")
                appendSequence(value.values.toList(), out)
                out.append('}')
            }

            is EdnValue.MapVal -> {
                out.append('{')
                appendMap(value.values, out)
                out.append('}')
            }

            is EdnValue.Symbol -> out.append(value.name)

            is EdnValue.Keyword -> {
                out.append(':')
                out.append(value.name)
            }

            is EdnValue.Tagged -> {
                out.append('#')
                out.append(value.tag)
                out.append(' ')
                append(value.value, out)
            }
        }
    }

    private fun appendSequence(values: List<EdnValue>, out: StringBuilder) {
        values.forEachIndexed { index, v ->
            if (index > 0) out.append(' ')
            append(v, out)
        }
    }

    private fun appendMap(values: Map<EdnValue, EdnValue>, out: StringBuilder) {
        var first = true
        for ((k, v) in values) {
            if (!first) out.append(' ')
            first = false
            append(k, out)
            out.append(' ')
            append(v, out)
        }
    }

    private fun appendString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
