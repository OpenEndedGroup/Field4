package afield.tools.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faster evidence matching must preserve the existing boundaries and literal-name semantics. */
class EvidenceTokenMatcherTest {
    @Test
    fun optimizedMatchingAgreesWithTheOriginalRule() {
        val names = listOf("ply", "run", "range", "cosine", "hslSet", "Vec3", "dataFiles", "a.b", "foo+bar", "δelta")
        val pages = listOf(
            "", "an unrelated paragraph", "apply(1); rerun(2); arrange(3)",
            "ply(1); run(2); range(3)", "cosineInterp(x); okhslSet(x)",
            "cosine (grey); hslSet (color)", "Vec3 Vec3Extra preVec3", "dataFiles.dataFiles",
            "a.b(1); axb(2); foo+bar(3); foobar(4)", "δelta(1); δelta Extraδelta", 
            names.joinToString("\n") { "$it()" }, names.joinToString(" "),
        )
        for (kind in listOf("call", "type", "name")) for (name in names) {
            val escaped = Regex.escape(name)
            val original = if (kind == "call") Regex("\\b$escaped\\(") else Regex("\\b$escaped\\b")
            val matcher = StdlibReferenceGenerator.EvidenceTokenMatcher(kind, name)
            for (page in pages) assertEquals(original.containsMatchIn(page), matcher.containsMatchIn(page),
                "kind=$kind name=$name page=$page")
        }
    }

    @Test
    fun aSuffixOrASpacedCallDoesNotBecomeEvidence() {
        val ply = StdlibReferenceGenerator.EvidenceTokenMatcher("call", "ply")
        assertFalse(ply.containsMatchIn("apply(x)"))
        assertFalse(ply.containsMatchIn("ply (x)"))
        assertTrue(ply.containsMatchIn("ply(x)"))
        val vector = StdlibReferenceGenerator.EvidenceTokenMatcher("type", "Vec3")
        assertFalse(vector.containsMatchIn("Vec3Extra"))
        assertTrue(vector.containsMatchIn("val p: Vec3"))
    }
}
