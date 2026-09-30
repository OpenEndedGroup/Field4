package afield.stdlib

import afield.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
}
