package afield

import afield.macros.gatherPreambleUpwards
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
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
 *
 * The third route is **load**, and it is the one the installed base runs on. Every nested box saved
 * before `398cb0ca9` is persisted with `nestedIn` set and no edge — 31 of the 33 live ones in a
 * 401-document workspace — so what a person actually opens is a document that must be repaired on the
 * way in. `reconcileNestingEdges` does that, and had done since `03ad66360`, unnamed and with no test
 * anywhere near it. The load tests below go through `SavingContext` rather than calling it, because
 * the contract is about what a stored document does when opened, and because the reconciliation is
 * worth nothing on a path that forgets to call it.
 *
 * Each load test asserts its own fixture first: that the boxes it is about to serialize really have
 * no container edge. Written the other way round, a change that made `setPropertyRaw` fire the
 * `Delegation` hooks would turn every one of them into a tautology that still passed.
 */
class NestingConnectsTest {

    private val boxes = mutableListOf<Box>()
    private val tempDirs = mutableListOf<File>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    /**
     * A session backed by a real (temporary) document directory, so `exportEdnPackage` and
     * `loadFromEdnText` can round-trip through the on-disk `metadata.edn` form. Its root is called
     * `root`, like a real document's, so that the `#afield/box "root"` refs in the exported text
     * resolve to the loading session's own root instead of conjuring a second one.
     */
    private fun fileBackedSession(label: String): SessionInformation {
        val dir = Files.createTempDirectory("afield-nesting-connects").toFile()
        tempDirs += dir
        val document = File(dir, label).also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{\"version\" 2 \"boxes\" []}\n")
        val root = Box("root")
        val s = SessionInformation(
            filename = document.absolutePath,
            documentName = label,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = s
        s.loading = false
        s.boxes[root.id] = root
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    private fun boxIn(s: SessionInformation, id: String) =
        Box(id).also { it.inside = s; s.boxes[id] = it; boxes += it }

    /**
     * Nest [child] in [container] the way a pre-`398cb0ca9` document got that way: straight onto the
     * property bag, which is what `nestInto`'s in-place `nested.add` amounted to as far as the
     * `Delegation` set-hooks were concerned. No edge is created, which is the point.
     */
    private fun nestWithoutConnecting(container: Box, child: Box) {
        container.setPropertyRaw("nested", container.nested.toMutableSet().apply { add(child) })
        child.setPropertyRaw("nestedIn", container)
    }

    private val markedPreamble = """
        import afield.stdlib.Inheritance
        var Box.sharedByMarker by Inheritance<Int>() /**/
        var Box.boxLocal by Inheritance<Int>()
    """.trimIndent()

    @AfterTest
    fun leaveTheGlobalResyncQueueAsWeFoundIt() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
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

    // ---------------------------------------------------------------------------------------------
    // The load route: a document already saved without the edge.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aStoredNestedBoxGainsItsContainerEdgeWhenTheDocumentIsLoaded() {
        val source = fileBackedSession("stored-nesting")
        val container = boxIn(source, "container").also { it.preamble = markedPreamble }
        val child = boxIn(source, "child").also { it.preamble = "" }
        source.root.connect(container)
        source.root.connect(child)
        nestWithoutConnecting(container, child)

        // The fixture's own premise, asserted rather than trusted. `parents` is persisted verbatim,
        // so a child whose only live parent is the document root is a child the file stores with no
        // container edge — which is the state 31 of 33 real nested boxes are in.
        assertEquals(setOf<Box>(source.root), child.parents.toSet(), "fixture must start with no edge")
        assertFalse("sharedByMarker" in gatherPreambleUpwards(child), "and inheriting nothing")

        val edn = SavingContext().exportEdnPackage(setOf(source.root, container, child), source.filename)
        assertTrue("\"nestedIn\"" in edn, "the export must carry the nesting itself:\n$edn")

        val opened = fileBackedSession("stored-nesting-opened")
        SavingContext().loadFromEdnText(edn, File(opened.filename).path, initIfCreated = false)

        val loadedContainer = opened.boxes["container"] ?: error("container did not load")
        val loadedChild = opened.boxes["child"] ?: error("child did not load")
        boxes += loadedContainer
        boxes += loadedChild

        assertEquals(loadedContainer, loadedChild.nestedIn, "nesting itself must survive the round trip")
        assertTrue(
            loadedContainer in loadedChild.parents,
            "opening the document must re-derive the container edge: ${loadedChild.parents}"
        )
        assertTrue(
            loadedChild in loadedContainer.children,
            "and the inverse: ${loadedContainer.children}"
        )
        assertTrue(
            opened.root in loadedChild.parents,
            "without disturbing the document-root edge: ${loadedChild.parents}"
        )

        val inherited = gatherPreambleUpwards(loadedChild)
        assertTrue("import afield.stdlib.Inheritance" in inherited, inherited)
        assertTrue("var Box.sharedByMarker" in inherited, inherited)
        assertFalse("var Box.boxLocal" in inherited, "an unmarked line still stays box-local:\n$inherited")
    }

    @Test
    fun loadingTheSameDocumentTwiceLeavesExactlyOneEdge() {
        // Idempotence is what lets the repair live on the load path at all: it runs on every open, so
        // it has to be a no-op the second time round rather than something that accumulates.
        val source = fileBackedSession("idempotent-load")
        val container = boxIn(source, "container")
        val child = boxIn(source, "child")
        source.root.connect(container)
        source.root.connect(child)
        nestWithoutConnecting(container, child)
        assertEquals(setOf<Box>(source.root), child.parents.toSet(), "fixture must start with no edge")

        val edn = SavingContext().exportEdnPackage(setOf(source.root, container, child), source.filename)

        val opened = fileBackedSession("idempotent-load-opened")
        val dir = File(opened.filename).path
        SavingContext().loadFromEdnText(edn, dir, initIfCreated = false)
        val loadedContainer = opened.boxes["container"] ?: error("container did not load")
        val loadedChild = opened.boxes["child"] ?: error("child did not load")
        boxes += loadedContainer
        boxes += loadedChild
        val afterFirst = loadedChild.parents.toList()

        SavingContext().loadFromEdnText(edn, dir, initIfCreated = false)

        assertEquals(afterFirst, loadedChild.parents.toList(), "a second load must add nothing")
        assertEquals(
            1,
            loadedContainer.children.count { it == loadedChild },
            "and must not duplicate the edge: ${loadedContainer.children}"
        )
    }

    @Test
    fun everyStoredNestedBoxUnderOneContainerGainsAnEdge() {
        // Sized on the worst real case: `moreNestingBehavior` holds seven live boxes nested in one
        // container and not one of them has a persisted edge. The floor is asserted, not the shape,
        // so shrinking the fixture cannot quietly weaken the claim.
        val nestedCount = 7
        val source = fileBackedSession("many-stored-nested")
        val container = boxIn(source, "container").also { it.preamble = markedPreamble }
        source.root.connect(container)
        val children = (0 until nestedCount).map { i ->
            boxIn(source, "child-$i").also { it.preamble = ""; source.root.connect(it) }
        }
        children.forEach { nestWithoutConnecting(container, it) }
        assertTrue(
            children.none { container in it.parents },
            "fixture must start with no edges at all"
        )

        val edn = SavingContext().exportEdnPackage(setOf(source.root, container) + children, source.filename)

        val opened = fileBackedSession("many-stored-nested-opened")
        SavingContext().loadFromEdnText(edn, File(opened.filename).path, initIfCreated = false)

        val loadedContainer = opened.boxes["container"] ?: error("container did not load")
        boxes += loadedContainer
        val gained = (0 until nestedCount).count { i ->
            val c = opened.boxes["child-$i"] ?: error("child-$i did not load")
            boxes += c
            loadedContainer in c.parents && "var Box.sharedByMarker" in gatherPreambleUpwards(c)
        }
        assertTrue(
            gained >= nestedCount,
            "all $nestedCount stored nested boxes should gain the edge and inherit; $gained did"
        )
    }

    @Test
    fun aDeletedBoxDoesNotGetItsContainerEdgeBackOnLoad() {
        // 11 boxes in the surveyed workspace are tombstoned and still carry `nestedIn`. Re-deriving an
        // edge for one puts a deleted box back among a live container's children — and, the other way
        // round, would feed a deleted container's preamble into a box the user can still see.
        val source = fileBackedSession("tombstoned-nesting")
        val container = boxIn(source, "container")
        val gone = boxIn(source, "gone")
        source.root.connect(container)
        nestWithoutConnecting(container, gone)
        gone.setPropertyRaw("_tombstone", true)
        assertFalse(gone in container.children, "fixture must start with no edge")

        val edn = SavingContext().exportEdnPackage(setOf(source.root, container, gone), source.filename)

        val opened = fileBackedSession("tombstoned-nesting-opened")
        SavingContext().loadFromEdnText(edn, File(opened.filename).path, initIfCreated = false)

        val loadedContainer = opened.boxes["container"] ?: error("container did not load")
        val loadedGone = opened.boxes["gone"] ?: error("gone did not load")
        boxes += loadedContainer
        boxes += loadedGone
        assertTrue(loadedGone.getPropertyRaw("_tombstone").isTrue(), "fixture must load as deleted")
        assertFalse(
            loadedGone in loadedContainer.children,
            "a deleted box must not be reconnected: ${loadedContainer.children}"
        )
        assertFalse(
            loadedContainer in loadedGone.parents,
            "in either direction: ${loadedGone.parents}"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Depth, and the cycle a nest edge now makes reachable in the `parents` graph.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aGrandchildInheritsThroughTwoLevelsOfNesting() {
        // `gatherPreambleUpwards` walks the whole ancestor fringe, not one hop, so a box nested inside
        // a box that is itself nested should see both. Nothing pinned that before.
        val s = session("deep-nest")
        val outer = boxIn(s, "outer").also { it.preamble = "var Box.fromOuter by Inheritance<Int>() /**/" }
        val middle = boxIn(s, "middle").also { it.preamble = "var Box.fromMiddle by Inheritance<Int>() /**/" }
        val inner = boxIn(s, "inner").also { it.preamble = "" }

        middle.nestInto(outer)
        inner.nestInto(middle)

        val inherited = gatherPreambleUpwards(inner)
        assertTrue("var Box.fromMiddle" in inherited, inherited)
        assertTrue("var Box.fromOuter" in inherited, "the grandparent's marked line should cross too:\n$inherited")
    }

    @Test
    fun nestingABoxIntoItselfIsRefused() {
        val s = session("self-nest")
        val box = boxIn(s, "box")

        box.nestInto(box)

        assertEquals(null, box.nestedIn, "a box cannot be nested in itself")
        assertFalse(box in box.nested, "and must not appear inside itself: ${box.nested}")
        assertFalse(box in box.parents, "nor become its own parent: ${box.parents}")
    }

    @Test
    fun nestingAContainerIntoSomethingAlreadyInsideItIsRefused() {
        // The reachable gesture: `-nest-into` nests every selected box into the first, so selecting a
        // box already inside the container and then the container asks for exactly this. Before the
        // nest edge existed the resulting cycle was confined to `nested`; now it would be written into
        // `parents`, which far more code walks.
        val s = session("descendant-nest")
        val outer = boxIn(s, "outer")
        val inner = boxIn(s, "inner")
        inner.nestInto(outer)

        outer.nestInto(inner)

        assertEquals(null, outer.nestedIn, "the cycle must be refused: outer.nestedIn=${outer.nestedIn}")
        assertEquals(inner.nestedIn, outer, "and the existing nesting left exactly as it was")
        assertFalse(inner in outer.parents, "refusing must not connect either: ${outer.parents}")
        assertFalse(outer in inner.nested, "nor nest: ${inner.nested}")
    }

    @Test
    fun nestingIntoADocumentThatAlreadyHoldsACycleStillTerminates() {
        // `nestedAgain2` in the surveyed workspace stores `35ee4ea3` and `79c4856c` as each other's
        // `nestedIn`. So the ancestor walk the refusal needs can be handed a cycle by the very first
        // document it is asked about, and has to carry its own `seen` set. If it does not, this test
        // hangs rather than fails, which is the honest signal for that bug.
        val s = session("pre-existing-cycle")
        val a = boxIn(s, "a")
        val b = boxIn(s, "b")
        val newcomer = boxIn(s, "newcomer")
        nestWithoutConnecting(a, b)
        nestWithoutConnecting(b, a)
        assertEquals(b, a.nestedIn, "fixture must be the cycle we meant to build")
        assertEquals(a, b.nestedIn, "fixture must be the cycle we meant to build")

        newcomer.nestInto(a)

        assertEquals(a, newcomer.nestedIn, "nesting into a box inside a cycle is still legitimate")
        assertTrue(a in newcomer.parents, "and still connects: ${newcomer.parents}")
    }

    @Test
    fun unNestingTakesTheEdgeEvenIfItWasThereForAnotherReason() {
        // Pinned as the deliberate behaviour it is, not as an oversight. `398cb0ca9` shipped this as a
        // declared residual asymmetry; it is settled by the same argument that licenses re-deriving an
        // edge on load — nothing in `parents` records *why* an edge is there, so an edge that existed
        // for another reason is indistinguishable from the derived one, and un-nest has to pick. It
        // picks removal, so the edge lives and dies with the nesting; reconnecting afterwards sticks,
        // because nothing will re-derive it away once the box is not nested.
        val s = session("un-nest-takes-the-edge")
        val container = boxIn(s, "container")
        val child = boxIn(s, "child")
        s.root.connect(child)
        container.connect(child)
        assertTrue(container in child.parents, "fixture: an edge that predates any nesting")

        child.nestInto(container)
        child.nestInto(null)

        assertEquals(null, child.nestedIn)
        assertFalse(container in child.parents, "un-nesting removes the edge, by design: ${child.parents}")
        assertTrue(s.root in child.parents, "and only that edge: ${child.parents}")
    }
}
