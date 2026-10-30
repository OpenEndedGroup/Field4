package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How [formatKotlinCode] lays out Kotlin 2.5 collection literals (`[a, b, c]` as an expression).
 *
 * Field formats with ktfmt's kotlinlang style on its experimental engine, where a multi-line call
 * stays on the line it starts (`val xs = listOf(` ... `)`). The jar Field ships carries a patch that
 * gives collection literals the same treatment, so a literal formats exactly as `listOf(...)` in the
 * same position with `[`/`]` in place of `listOf(`/`)`, and the first call chained onto either stays on
 * the closing bracket's line (`].forEach {`, `).map {`). A jar without those patches, or the stock
 * engine, fails here.
 */
class KotlinFormatterCollectionLiteralTest {

    private val looks = """
        |    Triple("soft", [3f, 0.20f, 1.5f], "first pass"),
        |    Triple("hot", [9f, 0.80f, 0.6f], "too much"),
        |    Triple("mid", [6f, 0.50f, 1.0f], "between the two"),
        |    Triple("wide", [12f, 0.35f, 2.2f], "for the long shot"),
    """.trimMargin()

    private fun asCalls(code: String) = code.replace("[\n", "listOf(\n").replace("\n]", "\n)")

    @Test
    fun aBrokenLiteralStaysOnTheAssignmentLine() {
        val code = "val xs = [\n$looks\n]\n"
        assertEquals(code, formatKotlinCode(code))
        assertEquals(asCalls(code), formatKotlinCode(asCalls(code)))
    }

    @Test
    fun aChainedLiteralFormatsLikeTheSameListOfCall() {
        // The first chained call stays on the closing bracket's line; later links go one per line at +4.
        val expected = "[\n$looks\n].forEach { (name, v, note) ->\n    println(name)\n}\n"
        val literal = formatKotlinCode("[\n$looks\n]\n    .forEach { (name, v, note) ->\n        println(name)\n    }\n")
        assertEquals(expected, literal)
        assertEquals(asCalls(expected), formatKotlinCode(asCalls(expected)))
        assertEquals(literal, formatKotlinCode(literal))
    }

    @Test
    fun laterLinksOfABlockLikeChainGoOnePerLine() {
        val code = "val r = listOf(\n    a,\n    b,\n).map { it * 2 }.filter { it > 0 }.sortedBy { -it }\n"
        val expected = "val r = listOf(\n    a,\n    b,\n).map { it * 2 }\n    .filter { it > 0 }\n    .sortedBy { -it }\n"
        assertEquals(expected, formatKotlinCode(code))
        assertEquals(expected, formatKotlinCode(expected))
        // A root that fits on one line is not block-like and keeps its one-line chain.
        val short = "val s = listOf(a, b).map { it * 2 }.filter { it > 0 }\n"
        assertEquals(short, formatKotlinCode(short))
    }

    @Test
    fun nestedLiteralsWrapAtTheFormattersWidth() {
        val three = (1..3).joinToString(", ") { "Triple(\"look$it\", [3f, 0.20f, 1.5f], \"note $it\")" }
        val formatted = formatKotlinCode("val m = mapOf(\"a\" to [$three])\n")
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
        assertEquals(formatted, formatKotlinCode(formatted))
    }

    @Test
    fun aLineThatFitsIsLeftOnOneLine() {
        val code = "val m = mapOf(\"a\" to [Triple(\"soft\", [3f, 0.20f, 1.5f], \"first pass\"), Triple(\"hot\", [9f, 0.80f, 0.6f], \"too much\")])\n"
        assertEquals(code, formatKotlinCode(code))
    }

    @Test
    fun emptyAndShortLiteralsAndAnnotationArgumentsAreUnchanged() {
        val code = "val e: List<Int> = []\nval short = [1, 2, 3]\n\n@Suppress([\"UNUSED\"])\nfun f() = 1\n"
        assertEquals(code, formatKotlinCode(code))
    }
}
