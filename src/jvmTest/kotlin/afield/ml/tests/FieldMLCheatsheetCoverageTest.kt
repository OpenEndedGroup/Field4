package afield.ml.tests

import afield.ml.type.FieldMLTypePrelude
import afield.ml.type.TypePrinter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cheatsheet is the lookup surface for the prelude, so it has to list all of it. This fails the
 * build when a name is added, removed or retyped without the page following, in the same spirit as
 * the RectSL operator coverage guard.
 */
class FieldMLCheatsheetCoverageTest {
    private val cheatsheetPath = Path.of("public/docs/fieldml_cheatsheet.md")

    /** Prelude entries pair a name and type in one cell with an explanation in the next. */
    private fun signatureRows(): Map<String, String> {
        val text = cheatsheetPath.toFile().readText()
        val row = Regex("""(?m)^\|\s*`([A-Za-z][A-Za-z0-9]*)`\s*<br>\s*`([^`]+)`\s*\|""")
        val rows = linkedMapOf<String, String>()
        row.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val previous = rows.put(name, match.groupValues[2])
            assertTrue(previous == null, "$cheatsheetPath lists '$name' more than once")
        }
        return rows
    }

    @Test
    fun everyPreludeNameIsListedWithItsType() {
        val listed = signatureRows()
        val expected = FieldMLTypePrelude.schemes.mapValues { (_, scheme) -> TypePrinter().printSignature(scheme.body) }

        val missing = expected.keys - listed.keys
        assertTrue(
            missing.isEmpty(),
            "$cheatsheetPath is missing ${missing.size} prelude name(s): ${missing.sorted().joinToString(", ")}",
        )

        val unknown = listed.keys - expected.keys
        assertTrue(
            unknown.isEmpty(),
            "$cheatsheetPath lists name(s) the prelude no longer has: ${unknown.sorted().joinToString(", ")}",
        )

        expected.forEach { (name, type) ->
            assertEquals(type, listed[name], "$cheatsheetPath has the wrong type for '$name'")
        }
    }

    /** Operators are not in the prelude map, so they get their own check against the lexer's vocabulary. */
    @Test
    fun everyOperatorIsListed() {
        val text = cheatsheetPath.toFile().readText()
        // The page groups related operators in one cell (`+ - ++`), so match tokens inside a code
        // span rather than whole spans. Table cells escape `|`, which is not part of the operator.
        val documented = Regex("""`([^`\n]+)`""")
            .findAll(text)
            .flatMap { it.groupValues[1].replace("\\", "").split(" ") }
            .toSet()
        val operators = listOf("||", "&&", "==", "!=", "<=", ">=", "<", ">", "::", "++", "+", "-", "*", "/", "|>")
        val missing = operators.filterNot { it in documented }
        assertTrue(missing.isEmpty(), "$cheatsheetPath does not document the operator(s): ${missing.joinToString(" ")}")
    }
}
