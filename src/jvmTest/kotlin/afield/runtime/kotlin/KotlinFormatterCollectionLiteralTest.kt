package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How [formatKotlinCode] lays out Kotlin 2.5 collection literals (`[a, b, c]` as an expression).
 *
 * The rule is parity with the call they stand for: a literal formats exactly as `listOf(...)` in the
 * same position, with `[`/`]` in place of `listOf(`/`)`, and a line holding nested literals wraps at
 * the formatter's width like nested calls do. These go through the ktfmt jar Field ships, so a jar
 * that treats literals as unbreakable or indents them differently from calls fails here.
 */
class KotlinFormatterCollectionLiteralTest {

    private val looks = """
        |    Triple("soft", [3f, 0.20f, 1.5f], "first pass"),
        |    Triple("hot", [9f, 0.80f, 0.6f], "too much"),
        |    Triple("mid", [6f, 0.50f, 1.0f], "between the two"),
        |    Triple("wide", [12f, 0.35f, 2.2f], "for the long shot"),
    """.trimMargin()

    private fun chained(open: String, close: String) =
        "val dir = \"x\"\n$open\n$looks\n$close.forEach { (name, v, note) ->\n    println(name)\n}\n"

    @Test
    fun aChainedLiteralFormatsLikeTheSameListOfCall() {
        val literal = formatKotlinCode(chained("[", "]"))
        val call = formatKotlinCode(chained("listOf(", ")"))
        assertEquals(call.replace("listOf(\n", "[\n").replace("\n    )\n", "\n    ]\n"), literal)
        assertEquals(
            """
            |val dir = "x"
            |[
            |        Triple("soft", [3f, 0.20f, 1.5f], "first pass"),
            |        Triple("hot", [9f, 0.80f, 0.6f], "too much"),
            |        Triple("mid", [6f, 0.50f, 1.0f], "between the two"),
            |        Triple("wide", [12f, 0.35f, 2.2f], "for the long shot"),
            |    ]
            |    .forEach { (name, v, note) ->
            |        println(name)
            |    }
            |
            """.trimMargin(),
            literal,
        )
        assertEquals(literal, formatKotlinCode(literal))
    }

    @Test
    fun nestedLiteralsWrapAtTheFormattersWidth() {
        val three = (1..3).joinToString(", ") { "Triple(\"look$it\", [3f, 0.20f, 1.5f], \"note $it\")" }
        val code = "val m = mapOf(\"a\" to [$three])\n"
        assertTrue(code.length > 121, "the input line must be over the width")
        val formatted = formatKotlinCode(code)
        assertEquals(
            """
            |val m = mapOf(
            |    "a" to
            |        [
            |            Triple("look1", [3f, 0.20f, 1.5f], "note 1"),
            |            Triple("look2", [3f, 0.20f, 1.5f], "note 2"),
            |            Triple("look3", [3f, 0.20f, 1.5f], "note 3"),
            |        ],
            |)
            |
            """.trimMargin(),
            formatted,
        )
        val asCalls = formatKotlinCode(code.replace("[", "listOf(").replace("]", ")"))
        assertEquals(asCalls.lines().size, formatted.lines().size, "a literal breaks where the call would")
        assertEquals(formatted, formatKotlinCode(formatted))
    }

    @Test
    fun aLineThatFitsIsLeftOnOneLine() {
        val code = "val m = mapOf(\"a\" to [Triple(\"soft\", [3f, 0.20f, 1.5f], \"first pass\"), Triple(\"hot\", [9f, 0.80f, 0.6f], \"too much\")])\n"
        assertEquals(code, formatKotlinCode(code))
    }

    @Test
    fun emptyAndShortLiteralsAndAnnotationArgumentsAreUnchanged() {
        val code = "val e: List<Int> = []\nval short = [1, 2, 3]\n\n@Suppress([\"UNUSED\"]) fun f() = 1\n"
        assertEquals(code, formatKotlinCode(code))
    }
}
