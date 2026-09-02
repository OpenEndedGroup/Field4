package afield

import afield.macros.gatherPreambleUpwards
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nesting a box connects it to its container, by every route that nests.
 *
 * `onNestedChange` / `onNestedInChange` have connected since `cbeded171` ("Auto-parent nested boxes to
 * nest container"), but [Box.nestInto] — the path the `-nest-into` / `-un-nest` actions and subtree
 * import all take — did not, because it runs inside the nesting re-entrancy guard those hooks bail out
 * of, and because it mutates `nested` in place where a `Delegation` set-hook cannot see it. So the two
 * halves of the same operation disagreed: `workspace/copy.kt` assigns `nestedIn` and connected, while
 * everything a person could actually do in the UI did not.
 *
 * These tests hold both routes to the same contract, and pin the consequence that made the divergence
 * matter: a connection edge is what carries the container's preamble to what is inside it.
 */
class NestingConnectsTest {

    private val boxes = mutableListOf<Box>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    private fun boxIn(s: SessionInformation, id: String) =
        Box(id).also { it.inside = s; s.boxes[id] = it; boxes += it }

    @AfterTest
    fun leaveTheGlobalResyncQueueAsWeFoundIt() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
    }

    @Test
    fun nestIntoConnectsTheContainerToTheBox() {
        val s = session("nest-connects")
        val container = boxIn(s, "container")
        val child = boxIn(s, "child")

        child.nestInto(container)

        assertEquals(container, child.nestedIn)
        assertTrue(child in container.nested, "nestInto should still nest")
        assertTrue(container in child.parents, "nesting must connect: ${child.parents}")
        assertTrue(child in container.children, "nesting must connect: ${container.children}")
    }

    @Test
    fun unNestingDisconnectsAndLeavesOtherEdgesAlone() {
        val s = session("un-nest-disconnects")
        val container = boxIn(s, "container")
        val child = boxIn(s, "child")
        // The edge a box normally arrives with, and which un-nesting must not touch.
        s.root.connect(child)

        child.nestInto(container)
        assertTrue(container in child.parents)

        child.nestInto(null)

        assertEquals(null, child.nestedIn)
        assertFalse(child in container.nested, "un-nesting should un-nest")
        assertFalse(container in child.parents, "un-nesting must disconnect: ${child.parents}")
        assertTrue(s.root in child.parents, "un-nesting must leave the document-root edge: ${child.parents}")
    }

    @Test
    fun reNestingMovesTheEdgeRatherThanAddingOne() {
        val s = session("re-nest")
        val first = boxIn(s, "first-container")
        val second = boxIn(s, "second-container")
        val child = boxIn(s, "child")

        child.nestInto(first)
        child.nestInto(second)

        assertEquals(second, child.nestedIn)
        assertTrue(second in child.parents, "the new container should be connected: ${child.parents}")
        assertFalse(first in child.parents, "the old container should be disconnected: ${child.parents}")
        assertFalse(child in first.nested)
    }

    @Test
    fun nestingIntoTheSameContainerTwiceIsIdempotent() {
        // The `oldParent != parent` check: re-nesting into the current container must not disconnect
        // the edge it is about to re-add, which would leave a torn `needsResync` trail for a no-op.
        val s = session("re-nest-same")
        val container = boxIn(s, "container")
        val child = boxIn(s, "child")

        child.nestInto(container)
        child.nestInto(container)

        assertEquals(container, child.nestedIn)
        assertTrue(container in child.parents, "still connected after a repeat nest: ${child.parents}")
        assertTrue(child in container.nested)
    }

    @Test
    fun assigningNestedConnectsTheSameWay() {
        // The hook route, for parity — this is what `workspace/copy.kt` does when it duplicates a
        // nested box, and it is the behaviour `nestInto` now matches rather than diverges from.
        val s = session("assign-nested")
        val container = boxIn(s, "container")
        val child = boxIn(s, "child")

        container.nested = container.nested.toMutableSet().apply { add(child) }

        assertEquals(container, child.nestedIn)
        assertTrue(container in child.parents, "assigning `nested` should connect: ${child.parents}")
    }

    @Test
    fun aNestedBoxInheritsTheContainersMarkedPreambleLines() {
        // Why the edge matters, and the concrete thing that was missing: `gatherPreambleUpwards` walks
        // `parents`, so with no edge a nested box saw none of its container's declarations. Imports and
        // trailing-marker lines cross; an unmarked line stays box-local either way.
        val s = session("nest-inherits-preamble")
        val container = boxIn(s, "container")
        container.preamble = """
            import afield.stdlib.Inheritance
            var Box.sharedByMarker by Inheritance<Int>() /**/
            var Box.boxLocal by Inheritance<Int>()
        """.trimIndent()
        val child = boxIn(s, "child").also { it.preamble = "" }

        assertFalse(
            "sharedByMarker" in gatherPreambleUpwards(child),
            "nothing should be inherited before nesting"
        )

        child.nestInto(container)

        val inherited = gatherPreambleUpwards(child)
        assertTrue("import afield.stdlib.Inheritance" in inherited, inherited)
        assertTrue("var Box.sharedByMarker" in inherited, inherited)
        assertFalse("var Box.boxLocal" in inherited, "an unmarked line stays box-local:\n$inherited")
    }
}
