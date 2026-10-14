package afield.document.properties

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.context.Why
import afield.document.context.Whying
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
import afield.runtime.lifecycle.Once
import afield.runtime.lifecycle.Onceing

import afield.geometry.mesh.contains
import afield.document.graph.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PropMacroRegressionTest {
    @Test
    fun propDoesNotImportDefaultPrimitiveTypes() {
        val insert = Box("test").macro_prop(listOf("<Boolean>", "(enabled)"))

        assertNotNull(insert)
        assertEquals("", insert.preamble)
        assertFalse(insert.preambleAfterImports.contains("import java.lang.Boolean"))
        assertFalse(insert.preambleAfterImports.contains("import kotlin.Boolean"))
        assertEquals("enabled", insert.here)
        assertEquals("Unit", insert.standaloneHere)
    }

    @Test
    fun propBareDeclarationStillGeneratesInheritedAccessors() {
        val insert = Box("test").macro_prop(listOf("<Int>", "(count)"))

        assertNotNull(insert)
        assertEquals("count", insert.here)
        assertEquals("Unit", insert.standaloneHere)
        assertEquals(
            """
            var Box.count by Inheritance<Int>() /**/
            val Once.count by Onceing<Int>() /**/
            val Why.count by Whying() /**/
            """.trimIndent(),
            insert.preambleAfterImports
        )
    }

    @Test
    fun propTakesADefaultForAMissingValue() {
        val insert = Box("test").macro_prop(listOf("<Int>", "(runs, 0)"))

        assertNotNull(insert)
        assertEquals("runs", insert.here)
        assertEquals(
            """
            var Box.runs by Inheritance<Int>(0) /**/
            val Once.runs by Onceing<Int>() /**/
            val Why.runs by Whying() /**/
            """.trimIndent(),
            insert.preambleAfterImports
        )
    }

    @Test
    fun aDefaultMayHoldCommas() {
        assertEquals("xs" to "listOf(1, 2)", propNameAndDefault("(xs, listOf(1, 2))"))
        assertEquals("count" to null, propNameAndDefault("(count)"))
    }

    @Test
    fun reactivePropsTakeADefaultToo() {
        val down = Box("test").macro_reactive_prop(listOf("<Int>", "(bpm, 120)"), Reactive.DOWN, "propDown")
        val up = Box("test").macro_reactive_prop(listOf("<Double>", "(scale, 1.0)"), Reactive.UP, "propUp")

        assertNotNull(down)
        assertNotNull(up)
        assertEquals("bpm", down.here)
        assertTrue(down.preambleAfterImports.contains("ReactiveInheritance<Int>(Reactive.DOWN, 120)"))
        assertTrue(up.preambleAfterImports.contains("ReactiveInheritance<Double>(Reactive.UP, 1.0)"))
    }
}
