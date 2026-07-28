@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.RunsAt
import afield.macroRegistration
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.macros.MacroRegistry.callRewrite

/**
 * Two related things: a small hand-written JSON reader, and a macro that generates a typed parser
 * from a terse schema. [parseJson] turns text into a [JValue] tree — [JObject], [JArray],
 * [JString], [JNumber] (which keeps the raw digits rather than converting), [JBool], [JNull] — and
 * throws [JsonParseException] with a character offset on bad input. No external JSON library is
 * involved.
 *
 * [JsonSchemaGen.generateSource] compiles a schema such as `{name: s, at: [f, f], tags: [s...],
 * ...}` into Kotlin source for a data class with a `fromString` companion: `f`/`i`/`s` are the
 * leaves, `[x...]` is a list, `[a, b]` is a `Pair` (2, 3 or 4 floats collapse to `Vec2`/`Vec3`/
 * `Vec4`), and a trailing `...` permits extra JSON fields instead of rejecting them. In a box you
 * reach this through the `jsonParser<Name>("schema")` macro registered by
 * [registerJsonParsingMacros], which inserts the generated source into the preamble. Inline nested
 * objects are not supported.
 */

/**
 * A node of a parsed JSON document — one of [JObject], [JArray], [JString], [JNumber], [JBool] or [JNull].
 *
 * Sealed, so a `when` over it is exhaustive without an `else`, which is the pleasant way to walk a document
 * whose shape you do not know ahead of time. When you *do* know the shape, the `jsonParser` macro generates a
 * typed data class instead and you never touch these.
 */
sealed interface JValue

/** A JSON object. Field order is whatever the [Map] preserves, and a duplicate key keeps the last occurrence. */
data class JObject(val fields: Map<String, JValue>) : JValue
/** A JSON array, in document order. Mixed element types are fine — each item is just a [JValue]. */
data class JArray(val items: List<JValue>) : JValue
/** A JSON string, with escapes already decoded — so [value] is the real text, not the source form. */
data class JString(val value: String) : JValue
/**
 * A JSON number, kept as **the raw digits** rather than converted.
 *
 * Deliberate: converting eagerly would decide for you between `Int`, `Long` and `Double` and could lose
 * precision on a large integer or a high-precision decimal before you ever saw it. Call `raw.toDouble()`,
 * `raw.toLong()` or `BigDecimal(raw)` according to what the field actually means.
 */
data class JNumber(val raw: String) : JValue
/** A JSON `true` or `false`. */
data class JBool(val value: Boolean) : JValue
/** JSON `null`. A singleton, so `value === JNull` works as well as `is JNull`. */
data object JNull : JValue

/**
 * Thrown by [parseJson] on malformed input, with the **character offset** in the message.
 *
 * The offset is what makes it useful on a long document — it points at where the reader gave up, which is
 * normally at or just after the mistake.
 */
class JsonParseException(message: String) : RuntimeException(message)


/**
 * Registers the `jsonParser<Name>("schema")` macro. Called by Field during macro registration, not by you.
 *
 * @throws afield.macros.MacroRegistry.MacroExpansionProblem at expansion time if the call site does not have
 * exactly a type argument and a schema string
 */
@RunsAt(macroRegistration)
fun registerJsonParsingMacros() {

    // jsonParser<NameOfClassToGenerate>("scheme")
    callRewrite["jsonParser"] = { box, id, args ->
        println("$box, $id, $args")

        if (args.size!=2) throw MacroRegistry.MacroExpansionProblem("jsonParser expects 2 arguments - jsonParser<NameOfClassToGenerate>(\"scheme\")")
        if (!args[0].startsWith("<") || !args[0].endsWith(">")) throw MacroRegistry.MacroExpansionProblem("jsonParser expects 2 arguments - jsonParser<NameOfClassToGenerate>(\"scheme\")")
        if (!args[1].startsWith("(\"") || !args[1].endsWith("\")")) throw MacroRegistry.MacroExpansionProblem("jsonParser expects 2 arguments - jsonParser<NameOfClassToGenerate>(\"scheme\")")
        val className = args[0].substring(1, args[0].length-1)
        val schemaText = args[1].substring(2, args[1].length-2)

        val src = JsonSchemaGen.generateSource(
            className,
            schemaText
        )


        Insert(preambleAfterImports = "/*jsonParser_${className}*/ /*start*/\n"+src+"\n/*end*/", here="")
    }
}

private class JsonReader(private val src: String) {
    private var i: Int = 0

    fun parse(): JValue {
        skipWs()
        val v = readValue()
        skipWs()
        if (i != src.length) fail("Unexpected trailing characters")
        return v
    }

    private fun readValue(): JValue {
        skipWs()
        if (i >= src.length) fail("Unexpected end of input")
        return when (src[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JString(readStringLiteral())
            't' -> {
                expectLiteral("true"); JBool(true)
            }

            'f' -> {
                expectLiteral("false"); JBool(false)
            }

            'n' -> {
                expectLiteral("null"); JNull
            }

            '-', in '0'..'9' -> JNumber(readNumberLiteral())
            else -> fail("Unexpected character '${src[i]}'")
        }
    }

    private fun readObject(): JObject {
        expect('{')
        skipWs()
        val map = linkedMapOf<String, JValue>()
        if (tryConsume('}')) return JObject(map)

        while (true) {
            skipWs()
            if (!peekIs('"')) fail("Expected string key")
            val key = readStringLiteral()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            if (tryConsume('}')) break
            expect(',')
        }
        return JObject(map)
    }

    private fun readArray(): JArray {
        expect('[')
        skipWs()
        val items = mutableListOf<JValue>()
        if (tryConsume(']')) return JArray(items)

        while (true) {
            items += readValue()
            skipWs()
            if (tryConsume(']')) break
            expect(',')
        }
        return JArray(items)
    }

    private fun readStringLiteral(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) fail("Unterminated string")
            when (val c = src[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= src.length) fail("Unterminated escape")
                    when (val e = src[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > src.length) fail("Bad unicode escape")
                            val hex = src.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }

                        else -> fail("Bad escape '\\$e'")
                    }
                }

                else -> sb.append(c)
            }
        }
    }

    private fun readNumberLiteral(): String {
        val start = i
        if (peekIs('-')) i++

        if (i >= src.length) fail("Bad number")
        if (src[i] == '0') {
            i++
        } else if (src[i] in '1'..'9') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        } else fail("Bad number")

        if (i < src.length && src[i] == '.') {
            i++
            if (i >= src.length || !src[i].isDigit()) fail("Bad number")
            while (i < src.length && src[i].isDigit()) i++
        }

        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            if (i >= src.length || !src[i].isDigit()) fail("Bad number")
            while (i < src.length && src[i].isDigit()) i++
        }

        return src.substring(start, i)
    }

    private fun expectLiteral(lit: String) {
        if (!src.startsWith(lit, i)) fail("Expected '$lit'")
        i += lit.length
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun expect(c: Char) {
        if (i >= src.length || src[i] != c) fail("Expected '$c'")
        i++
    }

    private fun tryConsume(c: Char): Boolean {
        if (i < src.length && src[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun peekIs(c: Char): Boolean = i < src.length && src[i] == c

    private fun fail(msg: String): Nothing {
        throw JsonParseException("$msg at JSON offset $i")
    }
}

/**
 * Parses JSON text into a [JValue] tree. No external library, no reflection, no schema needed.
 *
 * The one to use when a document's shape is unknown or irregular; when it is known and regular, the
 * `jsonParser<Name>("schema")` macro gives you a typed data class instead and is much less work to read.
 *
 * Numbers arrive as [JNumber] holding their raw digits rather than as a `Double`, so nothing is rounded before
 * you choose a type.
 *
 * @throws JsonParseException on malformed input, naming the character offset
 */
fun parseJson(src: String): JValue = JsonReader(src).parse()

/**
 * Turns a terse schema string into Kotlin source for a data class with a `fromString` companion — the code
 * generator behind the `jsonParser` macro.
 *
 * The schema language: `f`, `i` and `s` are the leaves (float, int, string); `[x...]` is a list of `x`;
 * `[a, b]` is a `Pair`, and 2, 3 or 4 floats collapse to `Vec2`/`Vec3`/`Vec4` instead; and a trailing `...` in
 * an object permits unknown JSON fields rather than rejecting the document. So
 * `{name: s, at: [f, f], tags: [s...], ...}` generates a class with a `String`, a `Vec2`, a `List<String>` and
 * tolerance for anything else the file carries.
 *
 * **Inline nested objects are not supported** — a field whose value is another `{…}` will not generate.
 */
object JsonSchemaGen {

    /**
     * Generates the Kotlin source for a data class named [className] that parses JSON matching [schemaText], with a
     * `fromString` companion function.
     *
     * Returns source *text* — it compiles nothing. The `jsonParser` macro is what puts the result into a box's
     * preamble; call this directly only when you want to read the code that would be generated.
     *
     * @throws IllegalArgumentException if the top-level schema is not an object
     */
    fun generateSource(
        className: String,
        schemaText: String
    ): String {
        val schema = SchemaParser(schemaText).parse()
        require(schema is SchemaNode.Obj) {
            "Top-level schema must be an object, got: $schema"
        }
        return KotlinGenerator(className, schema).generateSource()
    }

    sealed interface SchemaNode {
        data object FloatLeaf : SchemaNode
        data object IntLeaf : SchemaNode
        data object StringLeaf : SchemaNode
        data class Tuple(val items: List<SchemaNode>) : SchemaNode
        data class ListOf(val element: SchemaNode) : SchemaNode
        data class Obj(
            val fields: List<Field>,
            val allowExtraFields: Boolean
        ) : SchemaNode
    }

    data class Field(
        val name: String,
        val schema: SchemaNode
    )

    enum class TokKind {
        LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA, ELLIPSIS, IDENT, EOF
    }

    data class Tok(
        val kind: TokKind,
        val text: String,
        val pos: Int
    )

    class SchemaTokenizer(private val src: String) {
        private var i = 0

        fun tokenize(): List<Tok> {
            val out = mutableListOf<Tok>()
            while (true) {
                skipWs()
                if (i >= src.length) {
                    out += Tok(TokKind.EOF, "", i)
                    return out
                }
                when (val c = src[i]) {
                    '{' -> {
                        out += Tok(TokKind.LBRACE, "{", i); i++
                    }

                    '}' -> {
                        out += Tok(TokKind.RBRACE, "}", i); i++
                    }

                    '[' -> {
                        out += Tok(TokKind.LBRACKET, "[", i); i++
                    }

                    ']' -> {
                        out += Tok(TokKind.RBRACKET, "]", i); i++
                    }

                    ':' -> {
                        out += Tok(TokKind.COLON, ":", i); i++
                    }

                    ',' -> {
                        out += Tok(TokKind.COMMA, ",", i); i++
                    }

                    '.' -> {
                        if (src.startsWith("...", i)) {
                            out += Tok(TokKind.ELLIPSIS, "...", i)
                            i += 3
                        } else {
                            errorAt("Expected '...'", i)
                        }
                    }

                    else -> {
                        if (c.isLetter() || c == '_') {
                            val start = i
                            i++
                            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                            out += Tok(TokKind.IDENT, src.substring(start, i), start)
                        } else {
                            errorAt("Unexpected character '$c'", i)
                        }
                    }
                }
            }
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun errorAt(msg: String, pos: Int): Nothing {
            throw IllegalArgumentException("$msg at schema offset $pos")
        }
    }

    class SchemaParser(schemaText: String) {
        private val toks = SchemaTokenizer(schemaText).tokenize()
        private var i = 0

        fun parse(): SchemaNode {
            val n = parseSchema()
            expect(TokKind.EOF)
            return n
        }

        private fun parseSchema(): SchemaNode =
            when (peek().kind) {
                TokKind.LBRACE -> parseObject()
                TokKind.LBRACKET -> parseArray()
                TokKind.IDENT -> parseAtom()
                else -> fail("Expected schema node")
            }

        private fun parseAtom(): SchemaNode {
            val t = expect(TokKind.IDENT)
            return when (t.text) {
                "f" -> SchemaNode.FloatLeaf
                "i" -> SchemaNode.IntLeaf
                "s" -> SchemaNode.StringLeaf
                else -> fail("Unknown atom '${t.text}'", t.pos)
            }
        }

        private fun parseObject(): SchemaNode.Obj {
            expect(TokKind.LBRACE)
            val fields = mutableListOf<Field>()
            var allowExtra = false

            if (peek().kind != TokKind.RBRACE) {
                while (true) {
                    if (peek().kind == TokKind.ELLIPSIS) {
                        take()
                        allowExtra = true
                        break
                    }

                    val name = expect(TokKind.IDENT).text
                    expect(TokKind.COLON)
                    val schema = parseSchema()
                    fields += Field(name, schema)

                    if (peek().kind == TokKind.COMMA) {
                        take()
                        if (peek().kind == TokKind.ELLIPSIS) {
                            take()
                            allowExtra = true
                            break
                        }
                        continue
                    }
                    break
                }
            }

            expect(TokKind.RBRACE)
            return SchemaNode.Obj(fields, allowExtra)
        }

        private fun parseArray(): SchemaNode {
            expect(TokKind.LBRACKET)
            val first = parseSchema()

            return when (peek().kind) {
                TokKind.ELLIPSIS -> {
                    take()
                    expect(TokKind.RBRACKET)
                    SchemaNode.ListOf(first)
                }

                TokKind.COMMA -> {
                    val items = mutableListOf(first)
                    while (peek().kind == TokKind.COMMA) {
                        take()
                        items += parseSchema()
                    }
                    expect(TokKind.RBRACKET)
                    if (items.size == 1) fail("Singleton tuple [x] is not supported")
                    SchemaNode.Tuple(items)
                }

                TokKind.RBRACKET -> fail("Singleton tuple [x] is not supported")
                else -> fail("Expected '...', ',' or ']' in array")
            }
        }

        private fun peek(): Tok = toks[i]
        private fun take(): Tok = toks[i++]

        private fun expect(kind: TokKind): Tok {
            val t = peek()
            if (t.kind != kind) fail("Expected $kind but got ${t.kind}", t.pos)
            return take()
        }

        private fun fail(msg: String, pos: Int = peek().pos): Nothing {
            throw IllegalArgumentException("$msg at schema offset $pos")
        }
    }

    private class KotlinGenerator(
        private val className: String,
        private val root: SchemaNode.Obj
    ) {
        private val helpers = linkedMapOf<String, String>()

        fun generateSource(): String {
            val fieldsSrc = root.fields.joinToString(",\n") { f ->
                "    val ${safeIdent(f.name)}: ${kotlinType(f.schema)}"
            }

            val ctorArgs = root.fields.joinToString(",\n                ") { f ->
                val lookup =
                    """obj.fields["${f.name}"] ?: throw IllegalArgumentException("Missing field $.${f.name}")"""
                "${safeIdent(f.name)} = ${emitParseExpr(f.schema, lookup, "\"$.${f.name}\"")}"
            }

            val extraCheck = if (root.allowExtraFields) {
                ""
            } else {
                val allowed = root.fields.joinToString(", ") { "\"${it.name}\"" }
                """
val allowedKeys = setOf($allowed)
val extraKeys = obj.fields.keys - allowedKeys
require(extraKeys.isEmpty()) { "Unexpected fields at ${'$'}path: ${'$'}extraKeys" }
            """.trimIndent()
            }

            val helperBlock = helpers.values.joinToString("\n\n")

            return """
data class $className(
$fieldsSrc
) {
    companion object {
        operator fun invoke(s: String): $className = fromString(s)

        fun fromString(s: String): $className {
            val root = parseJson(s)
            return parse$className(root, "$")
        }

        private fun expectObject(v: JValue, path: String): JObject =
            v as? JObject ?: throw IllegalArgumentException("Expected object at ${'$'}path")

        private fun expectArray(v: JValue, path: String): JArray =
            v as? JArray ?: throw IllegalArgumentException("Expected array at ${'$'}path")

        private fun expectNumber(v: JValue, path: String): JNumber =
            v as? JNumber ?: throw IllegalArgumentException("Expected number at ${'$'}path")

        private fun expectStringValue(v: JValue, path: String): JString =
            v as? JString ?: throw IllegalArgumentException("Expected string at ${'$'}path")

        private fun parse$className(v: JValue, path: String): $className {
            val obj = expectObject(v, path)
${indent(extraCheck, 3)}
            return $className(
                $ctorArgs
            )
        }

        private fun parseFloat(v: JValue, path: String): Float {
            val n = expectNumber(v, path)
            return n.raw.toFloatOrNull()
                ?: throw IllegalArgumentException("Expected float at ${'$'}path but got '${'$'}{n.raw}'")
        }

        private fun parseInt(v: JValue, path: String): Int {
            val n = expectNumber(v, path)
            return n.raw.toIntOrNull()
                ?: throw IllegalArgumentException("Expected int at ${'$'}path but got '${'$'}{n.raw}'")
        }

        private fun parseString(v: JValue, path: String): String {
            val s = expectStringValue(v, path)
            return s.value
        }

$helperBlock
    }
}
        """.trimIndent()
        }

        private fun kotlinType(node: SchemaNode): String {
            return when (node) {
                SchemaNode.FloatLeaf -> "Float"
                SchemaNode.IntLeaf -> "Int"
                SchemaNode.StringLeaf -> "String"

                is SchemaNode.ListOf ->
                    "List<${kotlinType(node.element)}>"

                is SchemaNode.Tuple -> {
                    if (isFloatTuple(node, 2)) return "Vec2"
                    if (isFloatTuple(node, 3)) return "Vec3"
                    if (isFloatTuple(node, 4)) return "Vec4"

                    require(node.items.size == 2) {
                        "Only Pair<a,b> is supported for non-vector tuples, got ${node.items.size} items"
                    }
                    "Pair<${kotlinType(node.items[0])}, ${kotlinType(node.items[1])}>"
                }

                is SchemaNode.Obj ->
                    error("Inline nested objects are not supported yet")
            }
        }

        private fun emitParseExpr(
            node: SchemaNode,
            valueExpr: String,
            pathExpr: String
        ): String =
            when (node) {
                SchemaNode.FloatLeaf -> "parseFloat($valueExpr, $pathExpr)"
                SchemaNode.IntLeaf -> "parseInt($valueExpr, $pathExpr)"
                SchemaNode.StringLeaf -> "parseString($valueExpr, $pathExpr)"

                is SchemaNode.ListOf -> {
                    val fn = ensureListHelper(node.element)
                    "$fn($valueExpr, $pathExpr)"
                }

                is SchemaNode.Tuple -> when {
                    isFloatTuple(node, 2) -> {
                        ensureVec2Helper()
                        "parseVec2($valueExpr, $pathExpr)"
                    }

                    isFloatTuple(node, 3) -> {
                        ensureVec3Helper()
                        "parseVec3($valueExpr, $pathExpr)"
                    }

                    isFloatTuple(node, 4) -> {
                        ensureVec4Helper()
                        "parseVec4($valueExpr, $pathExpr)"
                    }

                    node.items.size == 2 -> {
                        val fn = ensurePairHelper(node.items[0], node.items[1])
                        "$fn($valueExpr, $pathExpr)"
                    }

                    else -> error("Unsupported tuple shape: $node")
                }

                is SchemaNode.Obj ->
                    error("Inline nested objects are not supported yet")
            }

        private fun ensureListHelper(element: SchemaNode): String {
            val name = "parse${typeTag(SchemaNode.ListOf(element))}"
            if (helpers.containsKey(name)) return name

            helpers[name] = """
private fun $name(v: JValue, path: String): List<${kotlinType(element)}> {
    val arr = expectArray(v, path)
    return arr.items.mapIndexed { i, item ->
        ${emitParseExpr(element, "item", "\"${'$'}path[${'$'}i]\"")}
    }
}
        """.trimIndent()

            return name
        }

        private fun ensurePairHelper(a: SchemaNode, b: SchemaNode): String {
            val name = "parse${typeTag(SchemaNode.Tuple(listOf(a, b)))}"
            if (helpers.containsKey(name)) return name

            helpers[name] = """
private fun $name(v: JValue, path: String): Pair<${kotlinType(a)}, ${kotlinType(b)}> {
    val arr = expectArray(v, path)
    require(arr.items.size == 2) { "Expected 2 items at ${'$'}path" }
    return Pair(
        ${emitParseExpr(a, "arr.items[0]", "\"${'$'}path[0]\"")},
        ${emitParseExpr(b, "arr.items[1]", "\"${'$'}path[1]\"")}
    )
}
        """.trimIndent()

            return name
        }

        private fun ensureVec2Helper() {
            if (helpers.containsKey("parseVec2")) return
            helpers["parseVec2"] = """
private fun parseVec2(v: JValue, path: String): Vec2 {
    val arr = expectArray(v, path)
    require(arr.items.size == 2) { "Expected 2 items at ${'$'}path" }
    return Vec2(
        parseFloat(arr.items[0], "${'$'}path[0]"),
        parseFloat(arr.items[1], "${'$'}path[1]")
    )
}
        """.trimIndent()
        }

        private fun ensureVec3Helper() {
            if (helpers.containsKey("parseVec3")) return
            helpers["parseVec3"] = """
private fun parseVec3(v: JValue, path: String): Vec3 {
    val arr = expectArray(v, path)
    require(arr.items.size == 3) { "Expected 3 items at ${'$'}path" }
    return Vec3(
        parseFloat(arr.items[0], "${'$'}path[0]"),
        parseFloat(arr.items[1], "${'$'}path[1]"),
        parseFloat(arr.items[2], "${'$'}path[2]")
    )
}
        """.trimIndent()
        }

        private fun ensureVec4Helper() {
            if (helpers.containsKey("parseVec4")) return
            helpers["parseVec4"] = """
private fun parseVec4(v: JValue, path: String): Vec4 {
    val arr = expectArray(v, path)
    require(arr.items.size == 4) { "Expected 4 items at ${'$'}path" }
    return Vec4(
        parseFloat(arr.items[0], "${'$'}path[0]"),
        parseFloat(arr.items[1], "${'$'}path[1]"),
        parseFloat(arr.items[2], "${'$'}path[2]"),
        parseFloat(arr.items[3], "${'$'}path[3]")
    )
}
        """.trimIndent()
        }

        private fun typeTag(node: SchemaNode): String {
            return when (node) {
                SchemaNode.FloatLeaf -> "Float"
                SchemaNode.IntLeaf -> "Int"
                SchemaNode.StringLeaf -> "String"

                is SchemaNode.ListOf ->
                    "ListOf${typeTag(node.element)}"

                is SchemaNode.Tuple -> {
                    if (isFloatTuple(node, 2)) return "Vec2"
                    if (isFloatTuple(node, 3)) return "Vec3"
                    if (isFloatTuple(node, 4)) return "Vec4"

                    require(node.items.size == 2) {
                        "Only Pair<a,b> supported for non-vector tuples"
                    }
                    "PairOf${typeTag(node.items[0])}And${typeTag(node.items[1])}"
                }

                is SchemaNode.Obj -> "Obj"
            }
        }

        private fun isFloatTuple(node: SchemaNode.Tuple, size: Int): Boolean =
            node.items.size == size && node.items.all { it is SchemaNode.FloatLeaf }

        private fun safeIdent(name: String): String =
            when (name) {
                "class", "object", "when", "is", "in", "fun", "val", "var" -> "`$name`"
                else -> name
            }

        private fun indent(s: String, levels: Int): String {
            if (s.isBlank()) return ""
            val prefix = "    ".repeat(levels)
            return s.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else prefix + line
            }
        }
    }
}
