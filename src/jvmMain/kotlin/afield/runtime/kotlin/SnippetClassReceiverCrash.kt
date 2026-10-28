package afield.runtime.kotlin

/**
 * Turns one K2 REPL crash into something a person (or an agent) can act on.
 *
 * In a box, a bare `println`, `print`, `display` or `error` is the [afield.ui.output.HasOutput] member
 * of the box, reached through the snippet's implicit receiver. Inside a `class` or `object` the box
 * declares, the frontend accepts that call but the script lowering has no path back to the snippet
 * instance and throws `Unexpected script transformation state: ScriptLikeToClassTransformerContext(...)`,
 * with no source location; the box then reports its last line. `class C { fun g() { println("hi") } }`
 * is enough (found 2026-10-28 in an agent's box; `kotlin.io.println` compiles).
 */
internal object SnippetClassReceiverCrash {
    const val SIGNATURE = "Unexpected script transformation state"

    /**
     * Calls as the compiler sees them, mapped to what the person wrote: Field's macros rewrite
     * `println(x)` to `_printlnHere(__line__+1, x)` and `print` to `_printHere` before compiling.
     */
    private val boxOutputMembers = mapOf(
        "println" to "println", "print" to "print", "display" to "display", "error" to "error",
        "_printlnHere" to "println", "_printHere" to "print"
    )

    /** A bare box-output call inside a class or object: 1-based line and column, the member and the declaration. */
    data class Site(val line: Int, val column: Int, val member: String, val declaration: String)

    fun matches(messages: List<String?>): Boolean = messages.any { it?.contains(SIGNATURE) == true }

    fun explain(site: Site?): String = buildString {
        append("\n\nInside a class or object declared in a box, the box itself is not reachable, so its ")
        append("`println`, `print`, `display` and `error` cannot be called there (a K2 REPL limitation; the compiler crashes ")
        append("instead of reporting it). ")
        if (site != null) append("Here: `${site.member}(...)` in `${site.declaration}`, line ${site.line}. ")
        append("Use `kotlin.io.println(...)`, return the value and print it outside the class, or pass the box in ")
        append("(`class W(val box: Box)` then `box.println(...)`).")
    }

    /** The first bare box-output call inside a class or object body in [source], or null. */
    fun locate(source: String): Site? {
        val code = blankStringsAndComments(source)
        val declaration = Regex("""\b(class|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        for (decl in declaration.findAll(code)) {
            val open = bodyStart(code, decl.range.last + 1) ?: continue
            val close = matchingBrace(code, open) ?: continue
            val call = Regex("""(?<![\w.:])(?<!fun )(_printlnHere|_printHere|println|print|display|error)\s*\(""").find(code.substring(open, close)) ?: continue
            val member = boxOutputMembers[call.groupValues[1]] ?: continue
            val at = open + call.range.first
            val line = 1 + code.substring(0, at).count { it == '\n' }
            val column = at - code.lastIndexOf('\n', at - 1)
            return Site(line, column, member, decl.groupValues[2])
        }
        return null
    }

    /**
     * The `{` that opens the body after a declaration's header, past a primary constructor, type
     * parameters and supertypes; null when the header ends first (a bodiless `class W`).
     */
    private fun bodyStart(code: String, from: Int): Int? {
        var depth = 0
        for (i in from until code.length) {
            when (code[i]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) return i
                '\n' -> if (depth <= 0) {
                    val header = code.substring(from, i).trimEnd()
                    val next = nextNonBlank(code, i)
                    if (!header.endsWith(",") && !header.endsWith(":") && next != '{' && next != ':' && next != ',') return null
                }
            }
        }
        return null
    }

    private fun nextNonBlank(code: String, from: Int): Char? {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        return code.getOrNull(i)
    }

    private fun matchingBrace(code: String, open: Int): Int? {
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}' && --depth == 0) return i
        }
        return null
    }

    /** Same length and line breaks as [source], with string literals and comments replaced by spaces. */
    internal fun blankStringsAndComments(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until minOf(to, out.length)) if (out[k] != '\n') out[k] = ' ' }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> { val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }; blank(i, end); i = end }
                source.startsWith("/*", i) -> { val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }; blank(i, end); i = end }
                source.startsWith("\"\"\"", i) -> { val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }; blank(i, end); i = end }
                source[i] == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') { if (source[j] == '\\') j++; j++ }
                    blank(i, j + 1); i = j + 1
                }
                source[i] == '\'' && i + 2 < source.length -> {
                    val end = source.indexOf('\'', i + 1 + (if (source[i + 1] == '\\') 1 else 0)).let { if (it < 0) i + 1 else it + 1 }
                    blank(i, end); i = end
                }
                else -> i++
            }
        }
        return out.toString()
    }
}
