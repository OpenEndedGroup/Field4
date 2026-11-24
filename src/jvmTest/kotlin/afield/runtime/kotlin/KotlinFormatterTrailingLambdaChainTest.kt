package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chain after a trailing lambda: ktfmt puts `.present()` on a line of its own and indents the whole
 * `presentation { … }` a continuation level, so a talk's steps sit eight spaces in. Marc's notes on
 * presentation_studio.md (2026-11-24) asked for `}.present()`; [tuckTrailingLambdaChains] restores
 * it. Every case formats twice to the same text.
 */
class KotlinFormatterTrailingLambdaChainTest {

    private fun format(code: String): String {
        val once = formatKotlinCode(code)
        assertEquals(once, formatKotlinCode(once), "formatting again changes nothing")
        return once
    }

    private val natural = """
        presentation(title = "A lesson about motion") {
            section("Opening") {
                step("The question") { audienceMarkdown("# What makes things move?") }
            }
            step("Discuss") { audienceMarkdown("# What changed?") }
        }.present()

    """.trimIndent()

    @Test
    fun theChainJoinsTheBraceAndTheBodyComesBackALevel() {
        val ktfmtShape = """
            presentation(title = "A lesson about motion") {
                    section("Opening") {
                        step("The question") { audienceMarkdown("# What makes things move?") }
                    }
                    step("Discuss") { audienceMarkdown("# What changed?") }
                }
                .present()

        """.trimIndent()
        assertEquals(natural, format(ktfmtShape))
    }

    @Test
    fun theNaturalShapeIsAlreadyFormatted() {
        assertEquals(natural, format(natural))
    }

    @Test
    fun aLongerChainJoinsWhole() {
        val formatted = format("presentation(title = \"t\") {\n    step(\"a\") { audienceMarkdown(\"# a\") }\n}.arranged().present()\n")
        assertTrue("\n}.arranged().present()" in formatted, formatted)
        assertTrue("\n    step(\"a\")" in formatted, formatted)
        assertFalse(formatted.lines().any { it.trim().startsWith(".") }, formatted)
    }

    @Test
    fun aCallWithNoArgumentsJoinsWithoutMoving() {
        val natural = "presentation {\n    step(\"a\") { audienceMarkdown(\"# a\") }\n}.present()\n"
        assertEquals(natural, format(natural))
        assertEquals(natural, format("presentation {\n    step(\"a\") { audienceMarkdown(\"# a\") }\n}\n    .present()\n"))
    }

    @Test
    fun aRawStringInsideKeepsItsValue() {
        val code = "presentation(title = \"t\") {\n    step(\"a\") {\n        audience {\n            +\"\"\"\n                ## Twice\n\n                as often\"\"\".md\n        }\n    }\n}.present()\n"
        val formatted = format(code)
        assertTrue("\n}.present()" in formatted, formatted)
        val content = Regex("\"\"\"([\\s\\S]*?)\"\"\"")
        assertEquals(content.find(code)!!.groupValues[1], content.find(formatted)!!.groupValues[1], "the string is unchanged")
    }
}
