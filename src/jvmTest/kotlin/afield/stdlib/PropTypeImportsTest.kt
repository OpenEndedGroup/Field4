package afield.stdlib

import afield.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `prop<T>`'s preamble is compiled apart from the box's code, so it carries the box's own imports for
 * the names in `T`: a nested type written through an imported outer class resolves.
 */
class PropTypeImportsTest {
    private fun boxWith(code: String) = Box("prop-imports-test").also { it.setPropertyRaw("code", code) }

    @Test
    fun aNestedTypeBringsTheBoxsImportOfItsOuterClass() {
        val box = boxWith("import afield.four.fabricated.StandardRenderer\nimport java.io.File\nprop<StandardRenderer.Bundle>(bundle)\n")
        val imports = box.propTypeImports("<StandardRenderer.Bundle>").lines()
        assertTrue("import afield.four.fabricated.StandardRenderer" in imports, imports.toString())
        assertTrue(imports.none { "java.io.File" in it }, "only imports naming the type come along: $imports")
    }

    @Test
    fun anAliasedImportCountsByItsAlias() {
        val box = boxWith("import afield.four.fabricated.StandardRenderer as SR\nprop<SR.Bundle>(bundle)\n")
        assertEquals(listOf("import afield.four.fabricated.StandardRenderer as SR"),
            box.propTypeImports("<SR.Bundle>").lines().filter { "SR" in it })
    }

    @Test
    fun aTypeArgumentsImportsComeAlongToo() {
        val box = boxWith("import afield.four.fabricated.StandardRenderer\nprop<List<StandardRenderer.Bundle>>(bundles)\n")
        assertTrue("import afield.four.fabricated.StandardRenderer" in box.propTypeImports("<List<StandardRenderer.Bundle>>").lines())
    }
}
