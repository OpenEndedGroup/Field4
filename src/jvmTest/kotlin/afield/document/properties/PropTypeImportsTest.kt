package afield.document.properties

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.document.graph.Box
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
        val box = boxWith("import afield.graphics.scene.fabricated.StandardRenderer\nimport java.io.File\nprop<StandardRenderer.Bundle>(bundle)\n")
        val imports = box.propTypeImports("<StandardRenderer.Bundle>").lines()
        assertTrue("import afield.graphics.scene.fabricated.StandardRenderer" in imports, imports.toString())
        assertTrue(imports.none { "java.io.File" in it }, "only imports naming the type come along: $imports")
    }

    @Test
    fun anAliasedImportCountsByItsAlias() {
        val box = boxWith("import afield.graphics.scene.fabricated.StandardRenderer as SR\nprop<SR.Bundle>(bundle)\n")
        assertEquals(listOf("import afield.graphics.scene.fabricated.StandardRenderer as SR"),
            box.propTypeImports("<SR.Bundle>").lines().filter { "SR" in it })
    }

    @Test
    fun aTypeArgumentsImportsComeAlongToo() {
        val box = boxWith("import afield.graphics.scene.fabricated.StandardRenderer\nprop<List<StandardRenderer.Bundle>>(bundles)\n")
        assertTrue("import afield.graphics.scene.fabricated.StandardRenderer" in box.propTypeImports("<List<StandardRenderer.Bundle>>").lines())
    }
}
