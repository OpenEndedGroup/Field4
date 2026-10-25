package afield.runtime.macros

import kotlin.test.Test
import kotlin.test.assertEquals

/** [fragmentBreakIndices]: explicit `//%-` breaks, and the implicit one between a shader seed and `moduleHere`. */
class FragmentBreakTest {
    private fun breaks(code: String) = fragmentBreakIndices(code.trimIndent().lines())

    @Test
    fun `explicit breaks are kept`() {
        assertEquals(listOf(1, 3), breaks("a()\n//%-\nb()"))
    }

    @Test
    fun `a moduleHere after a once vertex in one fragment gets its own fragment`() {
        val code = """
            once.vertex {
                ""${'"'}
                #version 450
                void main() { gl_Position = vec4(0); }
                ""${'"'}
            }
            val m = moduleHere { }
        """
        assertEquals(listOf(6, 7), breaks(code))
    }

    @Test
    fun `an explicit break already there adds nothing`() {
        assertEquals(listOf(1, 3), breaks("once.fragment { \"x\" }\n//%-\nmoduleHere { }"))
    }

    @Test
    fun `moduleHere with no seed before it, or nested in a block, is left alone`() {
        assertEquals(listOf(1), breaks("moduleHere { }"))
        assertEquals(listOf(4), breaks("once.vertex { \"v\" }\nif (true) {\n    moduleHere { }\n}"))
    }
}
