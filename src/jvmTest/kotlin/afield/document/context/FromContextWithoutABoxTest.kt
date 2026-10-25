package afield.document.context

import afield.document.graph.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Outside a running box (a main, a test) `fromContext` names the problem instead of NPE-ing on a null box. */
class FromContextWithoutABoxTest {
    @Test
    fun `fromContext with no box says so, and still honours a default`() {
        Box.context.remove()
        val e = assertFailsWith<IllegalStateException> { fromContext<StringBuilder>() }
        assertTrue("not running inside one" in e.message.orEmpty(), e.message)
        val fallback = StringBuilder("default")
        assertEquals(fallback, fromContext(fallback))
    }
}
