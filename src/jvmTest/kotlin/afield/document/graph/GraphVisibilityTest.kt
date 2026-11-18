package afield.document.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphVisibilityTest {
    private fun node(id: String) = Box(id = id).also { it.properties["visible"] = true }
    private fun link(parent: Box, child: Box) {
        parent.children.add(child)
        child.parents.add(parent)
    }

    @Test
    fun `walks a diamond once and never adds the anchor through a cycle`() {
        val a = node("visibility-a"); val b = node("visibility-b")
        val c = node("visibility-c"); val d = node("visibility-d")
        link(a, b); link(a, c); link(b, d); link(c, d); link(d, a)
        assertEquals(setOf(b, c, d), graphVisibilityTargets(a, GraphRelation.CHILDREN, false))
        assertEquals(setOf(b, c, d), graphVisibilityTargets(a, GraphRelation.PARENTS, false))
        assertEquals(setOf(a, b, c, d), graphVisibilityTargets(a, GraphRelation.CHILDREN, true))
    }

    @Test
    fun `root is a traversal barrier even in a malformed cycle`() {
        val root = node("root"); root.properties["visible"] = false
        val a = node("visibility-root-a"); val b = node("visibility-root-b")
        val unrelated = node("visibility-unrelated")
        link(root, a); link(root, unrelated); link(a, b); link(b, root)
        assertEquals(setOf(b), graphVisibilityTargets(a, GraphRelation.CHILDREN, false))
        assertEquals(setOf(a), graphVisibilityTargets(b, GraphRelation.PARENTS, false))
        assertTrue(graphVisibilityTargets(root, GraphRelation.CHILDREN, true).isEmpty())
        assertTrue(graphVisibilityTargets(unrelated, GraphRelation.PARENTS, false).isEmpty())
    }

    @Test
    fun `walks through hidden boxes and stops at deleted boxes`() {
        val a = node("visibility-hidden-a"); val b = node("visibility-hidden-b"); val c = node("visibility-hidden-c")
        link(a, b); link(b, c)
        b.properties["visible"] = false
        assertEquals(setOf(b, c), graphVisibilityTargets(a, GraphRelation.CHILDREN, false))
        b.properties["_tombstone"] = true
        assertTrue(graphVisibilityTargets(a, GraphRelation.CHILDREN, false).isEmpty())
        assertTrue(graphVisibilityTargets(b, GraphRelation.PARENTS, true).isEmpty())
    }

    @Test
    fun `visibility changes leave topology root and deleted boxes intact`() {
        val root = node("root"); root.properties["visible"] = false
        val a = node("visibility-save-a"); val b = node("visibility-save-b"); val deleted = node("visibility-deleted")
        link(root, a); link(a, b); deleted.properties["_tombstone"] = true
        assertEquals(1, applyGraphVisibility(graphVisibilityTargets(a, GraphRelation.CHILDREN, false), false))
        assertTrue(a.visible); assertFalse(b.visible)
        assertEquals(0, applyGraphVisibility(listOf(b, b), false))
        assertEquals(1, applyGraphVisibility(listOf(root, a, b, deleted), true))
        assertFalse(root.visible); assertTrue(deleted.visible); assertEquals(setOf(b), a.children)
        assertEquals(2, applyGraphVisibility(graphVisibilityTargets(b, GraphRelation.PARENTS, true), false))
        assertFalse(a.visible); assertFalse(b.visible)
        assertTrue(listOf("-hide-children", "-hide-with-children", "-reveal-children", "-hide-parents", "-hide-with-parents", "-reveal-parents", "-reveal-all-boxes").all { it in actionTable })
    }
}
