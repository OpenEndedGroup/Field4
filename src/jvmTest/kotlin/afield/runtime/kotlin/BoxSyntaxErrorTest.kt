package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Characterizes [FieldKtParser.firstSyntaxError], the check `KotlinInterface2` runs after the K2
 * REPL compiler reports success. The compiler accepts a snippet whose parse tree has error
 * elements whenever the recovered remainder type-checks, so `mapOf("circle" to [1, 2, 3), "egg"
 * to [4, 5])` used to compile to `{circle=[1, 2, 3]}` and fail later with "Key egg is missing in
 * the map". These pin that mismatched brackets are an error at the right place and that
 * well-formed literals are not.
 */
class BoxSyntaxErrorTest {

    @Test
    fun aLiteralClosedWithAParenIsAnError() {
        val code = "val a = 1\nval m = mapOf(\"circle\" to [1, 2, 3), \"egg\" to [4, 5])\n"
        val error = assertNotNull(FieldKtParser.firstSyntaxError(code))
        assertEquals(2, error.line)
        // The parser reports at the `)` that does not close `[`.
        assertEquals(code.lines()[1].indexOf(')') + 1, error.column)
    }

    @Test
    fun aCallClosedWithABracketIsAnError() {
        val error = assertNotNull(FieldKtParser.firstSyntaxError("val m = mapOf(\"a\" to listOf(1, 2], \"b\" to 3)\n"))
        assertEquals(1, error.line)
    }

    @Test
    fun aStrayBracketInAnExpressionIsAnError() {
        assertNotNull(FieldKtParser.firstSyntaxError("println(1 + 2])\n"))
        assertNotNull(FieldKtParser.firstSyntaxError("val q = [1, 2)\n"))
    }

    @Test
    fun wellFormedNestedLiteralsParse() {
        val code = """
            |val m = mapOf("circle" to [1, 2, 3], "egg" to [[4, 5], [6]])
            |val xs = listOf([1.0, 2.0], [3.0])
            |val s = ["a)", "(b]", "[c", "${'$'}{[1, 2].size})"]
            |val t = Triple("x", [m["egg"], m.getValue("circle")], foo(bar[0], [baz(1)]))
            |val multi = [
            |    "first ]",
            |    ""${'"'}raw ( [ ""${'"'},
            |]
            |val c = ']'
        """.trimMargin()
        assertNull(FieldKtParser.firstSyntaxError(code))
    }
}
