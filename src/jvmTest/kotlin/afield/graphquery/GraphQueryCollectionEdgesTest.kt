package afield.graphquery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gaps the inspector (`tree(...)` / `column(...)`, which lists exactly [GraphWalker.edges]) exposed
 * once it was rebased on this walker.
 */
class GraphQueryCollectionEdgesTest {
    data class Tag(val name: String)
    data class Holder(val tags: Set<Tag>)

    @Test
    fun `a set is walked by position, read-only`() {
        val h = Holder(linkedSetOf(Tag("a"), Tag("b")))
        assertEquals(listOf("tags/0/name", "tags/1/name"), h.graphPaths("**/name").paths())
        val ref = h.graphPath("tags/1")!!
        assertEquals(Tag("b"), ref.get())
        assertFalse(ref.writable)
        assertEquals(listOf("tags/0", "tags/1"), h.graphPaths("tags/*").paths())
    }

    @Test
    fun `a node reflection cannot read does not abort the walk`() {
        // A Kotlin lambda's class is refused by kotlin-reflect; a function is not descended, but an
        // anonymous object's class reaches reflection directly.
        val odd = object { val inside = Tag("x") }
        val root = mapOf("odd" to odd, "ok" to Tag("y"))
        val names = root.graphPaths("**/name").paths()
        assertTrue("ok/name" in names, names.toString())
    }

    @Test
    fun `edges lists what the walk descends, and nothing for a leaf`() {
        assertEquals(listOf("name"), GraphWalker.edges(Tag("t"), GraphWalkOptions()).map { it.name })
        assertTrue(GraphWalker.edges("a string", GraphWalkOptions()).isEmpty())
        assertTrue(GraphWalker.edges(java.time.LocalTime.NOON, GraphWalkOptions()).isEmpty())
    }
}
