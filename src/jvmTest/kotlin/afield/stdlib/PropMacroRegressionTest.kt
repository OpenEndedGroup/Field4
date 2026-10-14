package afield.stdlib

import afield.Box
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
