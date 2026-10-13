package afield

import kotlin.test.Test
import kotlin.test.assertEquals

/** [analyzeBoxImports] and [formatBoxImports], against a small intro of their own. */
class BoxImportsTest {
    private val intro = "import afield.*\nimport afield.stdlib.*\nimport afield.stdlib.Vec2\n"

    private fun verdicts(code: String, evidence: K2ImportEvidence? = null, candidates: (String) -> Collection<String> = { emptyList() }) =
        analyzeBoxImports(code, intro, evidence, candidates).associate { it.import.path to it.verdict }

    @Test
    fun importsTheIntroAlreadyHasAreRedundant() {
        val v = verdicts("import afield.stdlib.Vec2\nimport afield.stdlib.*\nimport afield.stdlib.lerp\nval a = Vec2(lerp(0, 1, 0.5), 0)")
        assertEquals(ImportVerdict.REDUNDANT, v["afield.stdlib.Vec2"])
        assertEquals(ImportVerdict.REDUNDANT, v["afield.stdlib.*"])
        assertEquals(ImportVerdict.REDUNDANT, v["afield.stdlib.lerp"])
    }

    @Test
    fun anExplicitImportIsUsedOnlyIfTheCodeNamesItOutsideCommentsAndStrings() {
        val v = verdicts("import java.io.File\nimport java.net.URL\n// File in a comment\nval s = \"URL\"\nval f = File(\"x\")")
        assertEquals(ImportVerdict.NEEDED, v["java.io.File"])
        assertEquals(ImportVerdict.UNUSED, v["java.net.URL"])
    }

    @Test
    fun anOperatorImportIsKeptAlthoughItsNameNeverAppears() {
        assertEquals(ImportVerdict.KEPT, verdicts("import afield.four.modules.plusAssign\nmodule += mesh")["afield.four.modules.plusAssign"])
    }

    @Test
    fun anExplicitImportThatDisambiguatesBetweenIntroStarsIsKept() {
        val code = "import afield.stdlib.Grid\nval g = Grid()"
        val clash = verdicts(code) { listOf("afield.stdlib.Grid", "afield.Grid") }
        assertEquals(ImportVerdict.NEEDED, clash["afield.stdlib.Grid"])
        assertEquals(ImportVerdict.REDUNDANT, verdicts(code)["afield.stdlib.Grid"])
    }

    @Test
    fun aStarImportIsDroppedOnlyOnCompleteK2Evidence() {
        val code = "import afield.four.fabricated.*\nval b = build()"
        assertEquals(ImportVerdict.KEPT, verdicts(code)["afield.four.fabricated.*"])
        assertEquals(ImportVerdict.NEEDED, verdicts(code, K2ImportEvidence(setOf("afield.four.fabricated.build"), 0))["afield.four.fabricated.*"])
        assertEquals(ImportVerdict.KEPT, verdicts(code, K2ImportEvidence(setOf("afield.stdlib.build"), 1))["afield.four.fabricated.*"])
        assertEquals(ImportVerdict.UNUSED, verdicts(code, K2ImportEvidence(setOf("afield.stdlib.build"), 0))["afield.four.fabricated.*"])
    }

    @Test
    fun anAliasIsNeverRedundant() {
        assertEquals(ImportVerdict.NEEDED, verdicts("import afield.stdlib.Vec2 as V\nval v = V(0, 0)")["afield.stdlib.Vec2"])
    }

    @Test
    fun formattingDropsRedundantAndUnusedImportsAndSortsTheRest() {
        val code = "import java.io.File\nimport afield.stdlib.Vec2\n// why the URL\nimport java.net.URL\nimport afield.four.fabricated.*\n\nval f = File(\"x\")\n"
        assertEquals(
            "import afield.four.fabricated.*\nimport java.io.File\n// why the URL\n\nval f = File(\"x\")\n",
            formatBoxImports(code, analyzeBoxImports(code, intro, null) { emptyList() }),
        )
    }

    @Test
    fun aBlockEmptiedEntirelyTakesItsBlankLineWithIt() {
        val code = "import afield.stdlib.Vec2\n\nval v = Vec2(0, 0)\n"
        assertEquals("val v = Vec2(0, 0)\n", formatBoxImports(code, analyzeBoxImports(code, intro, null) { emptyList() }))
    }

    @Test
    fun codeWithNothingToChangeComesBackIdentical() {
        val code = "import java.io.File\nval f = File(\"x\")"
        assertEquals(code, formatBoxImports(code, analyzeBoxImports(code, intro, null) { emptyList() }))
    }
}
