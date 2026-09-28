package afield.pd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PdObjects]: the nearest object Pd can create to one it could not. The suggestions are checked
 * against a small fixed catalog, so they need no libpd; the last test reads the real catalog the
 * libpd build writes, when there is one.
 */
class PdObjectsTest {
    private val known = setOf("osc~", "phasor~", "lop~", "hip~", "metro", "line", "line~", "dac~", "r", "s", "t", "list append", "list")

    @Test fun aMissingTildeIsTheSignalTwin() {
        assertEquals(listOf("osc~ 440"), PdObjects.suggest("osc 440", known))
        assertEquals(listOf("metro 100"), PdObjects.suggest("metro~ 100", known))
    }

    @Test fun caseAndSmallMisspellingsFindTheNearestName() {
        assertEquals(listOf("metro 100"), PdObjects.suggest("Metro 100", known))
        assertEquals(listOf("phasor~ 2"), PdObjects.suggest("phsor~ 2", known))
        assertEquals(listOf("phasor~"), PdObjects.suggest("phaosr~", known), "an adjacent swap is one edit")
        // lop~ and hip~ are both one edit from lip~: equally close, both offered, in order.
        assertEquals(listOf("hip~ 200", "lop~ 200"), PdObjects.suggest("lip~ 200", known))
    }

    @Test fun nothingIsOfferedForAKnownNameOrAFarOne() {
        assertTrue(PdObjects.suggest("lop~ 1200", known).isEmpty(), "a known object failed for another reason")
        assertTrue(PdObjects.suggest("granulator~", known).isEmpty())
        // Three letters or fewer allow one edit only, or every short name would be near every other.
        assertTrue(PdObjects.suggest("xyz", known).isEmpty())
        assertTrue(PdObjects.suggest("osc 440", emptySet()).isEmpty(), "no catalog, no guess")
    }

    @Test fun theHintReadsAsAQuestion() {
        assertEquals("did you mean [osc~ 440]?", PdObjects.hint("osc 440", known))
        assertEquals("did you mean [hip~] or [lop~]?", PdObjects.hint("lip~", known))
        assertEquals("", PdObjects.hint("lop~", known))
    }

    @Test fun theProblemOnTheLineCarriesTheHint() {
        val source = "#N canvas 0 0 400 300 12;\n#X obj 20 20 osc 440;\n#X obj 20 60 dac~;\n"
        val problems = PdPatch.problemsIn(source, listOf("verbose(0): osc 440", "error: ... couldn't create"), known)
        assertEquals(1, problems.size, "$problems")
        assertEquals(1, problems[0].line)
        assertEquals("Pd couldn't create [osc 440] — did you mean [osc~ 440]?", problems[0].message)
    }

    @Test fun theBuiltCatalogNamesVanillaObjectsAndTheirAliases() {
        val catalog = PdObjects.catalog()
        if (catalog.isEmpty()) {
            println("SKIPPED the built-catalog check: no ${PdObjects.FILE_NAME} beside ${PdLibrary.describe()}")
            return
        }
        for (name in listOf("osc~", "phasor~", "lop~", "dac~", "r", "s", "t", "hsl", "metro", "expr~", "tabosc4~", "loadbang"))
            assertTrue(name in catalog, "$name is missing from the catalog of ${catalog.size}")
        assertTrue("canvasmaker" !in catalog, "Pd's internal classes are left out")
        assertEquals(listOf("osc~ 440"), PdObjects.suggest("osc 440"))
    }
}
