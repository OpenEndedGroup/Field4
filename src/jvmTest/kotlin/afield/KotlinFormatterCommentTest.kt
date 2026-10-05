package afield

import kotlin.test.Test
import afield.macros.isFragmentBreak
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract for collapsing runs of line comments: a run of three or more becomes one block
 * comment, everything that is not such a run is left exactly as it was, and formatting twice
 * gives the same text as formatting once.
 *
 * The runs in the left-alone cases are three lines long wherever the rule under test allows it,
 * so that they fail if the rule stops working rather than passing on the length alone.
 */
class KotlinFormatterCommentTest {

    // ------------------------------------------------------- what gets collapsed

    @Test
    fun aRunOfThreeBecomesOneBlockComment() {
        assertEquals(
            "/*\nfirst\nsecond\nthird\n*/\nval a = 1",
            blockifyLineCommentRuns("// first\n// second\n// third\nval a = 1")
        )
    }

    @Test
    fun theRunKeepsItsIndentation() {
        assertEquals(
            "fun f() {\n    /*\n    first\n    second\n    third\n    */\n    val a = 1\n}",
            blockifyLineCommentRuns("fun f() {\n    // first\n    // second\n    // third\n    val a = 1\n}")
        )
    }

    @Test
    fun anEmptyLineInsideTheRunStaysEmptyRatherThanBecomingSpaces() {
        assertEquals(
            "/*\nfirst\n\nsecond\n*/\nval a = 1",
            blockifyLineCommentRuns("// first\n//\n// second\nval a = 1")
        )
    }

    @Test
    fun indentationInsideTheBodyIsKept() {
        assertEquals(
            "fun f() {\n    /*\n    first\n      nested detail\n    last\n    */\n    val a = 1\n}",
            blockifyLineCommentRuns("fun f() {\n    // first\n    //   nested detail\n    // last\n    val a = 1\n}")
        )
    }

    // ------------------------------------------------------- what is left alone

    @Test
    fun aSingleLineCommentIsNotAMultiLineComment() {
        val code = "// alone\nval a = 1"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun aRunOfTwoIsBelowTheThreshold() {
        val code = "// first\n// second\nval a = 1"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun trailingCommentsOnCodeLinesAreNotARun() {
        val code = "val a = 1 // one\nval b = 2 // two\nval c = 3 // three"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun aBlankLineSplitsTheRun() {
        // Four lines, so only the blank line keeps either half under the threshold.
        val code = "// first\n// second\n\n// third\n// fourth\nval a = 1"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun aRunHoldingABlockDelimiterIsLeftAloneBecauseKotlinBlockCommentsNest() {
        val open = "// opens with /*\n// and more\n// and more again\nval a = 1"
        assertEquals(open, blockifyLineCommentRuns(open))
        val close = "// closes with */\n// and more\n// and more again\nval a = 1"
        assertEquals(close, blockifyLineCommentRuns(close))
    }

    @Test
    fun linesInsideARawStringAreNotComments() {
        val code = buildString {
            appendLine("val raw =")
            appendLine("    \"\"\"")
            appendLine("    // not a comment")
            appendLine("    // nor this")
            appendLine("    // nor this either")
            appendLine("    \"\"\"")
            append("val a = 1")
        }
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun aDoubleSlashInsideAStringIsNotAComment() {
        val code = "val url = \"https://example.com//path\"\nval a = 1"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    @Test
    fun anExistingBlockCommentIsUntouched() {
        val code = "/*\n * first\n * second\n * third\n */\nval a = 1"
        assertEquals(code, blockifyLineCommentRuns(code))
    }

    // ------------------------------------------------------- through the real formatter

    @Test
    fun theFormatterCollapsesRunsAndIsAFixedPoint() {
        val once = formatKotlinCode(
            """
            fun f() {
                // first
                // second
                // third
                val a = 1 // trailing
            }
            """.trimIndent()
        )
        assertEquals(
            """
            fun f() {
                /*
                first
                second
                third
                */
                val a = 1 // trailing
            }
            """.trimIndent(),
            once.trim()
        )
        assertEquals(once, formatKotlinCode(once), "formatting twice must not change the text")
    }

    @Test
    fun aFragmentBreakKeepsItsSpellingThroughTheFormatter() {
        val once = formatKotlinCode("val a = 1\n\n//%-\nval b = 2\n    //%- indented\nval c = 3\n")
        assertEquals(listOf("//%-", "//%- indented"), once.lines().filter { "%-" in it }.map(String::trim))
        assertEquals(once, formatKotlinCode(once), "formatting twice must not change the text")
        assertTrue(once.lines().filter { "%-" in it }.all(::isFragmentBreak))
    }

    @Test
    fun spacedFragmentBreaksAreStillBreaks() {
        assertTrue(isFragmentBreak("// %-"))
        assertTrue(isFragmentBreak("  /* %- */"))
        assertTrue(isFragmentBreak("//%-"))
        assertFalse(isFragmentBreak("val s = \"//%-\""))
        assertFalse(isFragmentBreak("// 50%-ish"))
    }

    @Test
    fun commentsAfterTheLastStatementStayOnTheirOwnLines() {
        val once = formatKotlinCode("loop {\n    true\n}\n// one\n// two\n")
        assertEquals("loop {\n    true\n}\n\n// one\n// two\n", once)
        assertEquals(once, formatKotlinCode(once), "formatting twice must not change the text")
    }

    @Test
    fun aMultiLineCallStaysOnItsAssignmentLine() {
        val code = "val a = sim.addCloth(\n    width = 22,\n    // note\n    height = 22,\n)\nval c = sim.addCloth(width = 22)\n"
        val once = formatKotlinCode(code)
        assertEquals("val a = sim.addCloth(\n    width = 22,\n    // note\n    height = 22,\n)\nval c = sim.addCloth(width = 22)\n", once)
        assertEquals(once, formatKotlinCode(once), "formatting twice must not change the text")
    }

    @Test
    fun aRawStringIsNeverReindented() {
        val code = "val s =\n    listOf(\n        \"\"\"\n        x\n        \"\"\"\n    )\n"
        assertEquals(code, joinAssignmentBreaks(code))
    }

    @Test
    fun consecutiveStatementsStayConsecutiveAndAuthoredBlankLinesStay() {
        val code = "buf.add(1.0)\nbuf.add(2.0) // c\n\nval x = 1\nfun f() {\n    g()\n    h()\n}\n"
        val once = formatKotlinCode(code)
        assertEquals(code, once)
        assertEquals(once, formatKotlinCode(once), "formatting twice must not change the text")
    }

    @Test
    fun theCollapseCanBeTurnedOff() {
        val code = "fun f() {\n    // first\n    // second\n    // third\n    val a = 1\n}"
        assertEquals(code, formatKotlinCode(code, blockifyComments = false).trim())
    }
}
