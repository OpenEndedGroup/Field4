package afield

import afield.stdlib.MissingInheritedPropertyException
import afield.stdlib.Reactive
import afield.stdlib.ReactiveInheritance
import afield.stdlib.inheritedPropertyRaw
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private var Box.reactiveTestDown: Int? by ReactiveInheritance<Int?>(Reactive.DOWN)
private var Box.reactiveTestUp: Int by ReactiveInheritance<Int>(Reactive.UP)

/**
 * The parts of [ReactiveRuns] that do not need a running Field: cascade ordering, supplier
 * discovery, change noting, and how a pull fails without a runloop. The runs themselves — nested
 * pulls, cascades, cycles, startup — are `tools/reactive_props_scenarios.py`, against a live Field.
 */
class ReactivePropsTest {

    private val sessions = mutableListOf<SessionInformation>()
    private val boxes = mutableListOf<Box>()

    private fun session(label: String): SessionInformation {
        val root = Box("$label-root")
        val s = SessionInformation(filename = "<reactive-$label>", documentName = label, root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        sessions += s
        boxes += root
        PropertyDependencies.forget(s.filename)
        return s
    }

    private fun boxIn(s: SessionInformation, id: String, code: String = ""): Box =
        Box(id).also {
            it.inside = s
            it.name = id
            s.boxes[id] = it
            boxes += it
            if (code.isNotEmpty()) it.setPropertyRaw("code", code)
        }

    private fun <T> Box.running(block: () -> T): T {
        val previous = Box.context.get()
        Box.context.set(this)
        try {
            return block()
        } finally {
            if (previous == null) Box.context.remove() else Box.context.set(previous)
        }
    }

    @AfterTest
    fun cleanUp() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        sessions.forEach { PropertyDependencies.forget(it.filename) }
        sessions.clear()
        boxes.clear()
        Box.context.remove()
    }

    // ---------------------------------------------------------------------------------------------
    // Cascade order

    private val diamond = mapOf("A" to setOf("B", "C"), "B" to setOf("D"), "C" to setOf("D"))

    @Test
    fun aDiamondsBottomWaitsForBothSides() {
        assertEquals("B", ReactiveRuns.orderNext(listOf("B", "C", "D"), diamond))
        assertEquals("C", ReactiveRuns.orderNext(listOf("C", "D"), diamond))
        assertEquals("D", ReactiveRuns.orderNext(listOf("D"), diamond))
    }

    @Test
    fun anEarlierChangeDoesNotJumpTheQueueAheadOfItsOwnUpstream() {
        // D's change came first, but B can still change D's input.
        assertEquals("B", ReactiveRuns.orderNext(listOf("D", "B"), mapOf("A" to setOf("B", "D"), "B" to setOf("D"))))
    }

    @Test
    fun reachabilityIsTransitiveThroughBoxesThatAreNotWaiting() {
        // A -> X -> D, with only A and D waiting: D must wait for A even though X is not waiting.
        assertEquals("A", ReactiveRuns.orderNext(listOf("D", "A"), mapOf("A" to setOf("X"), "X" to setOf("D"))))
    }

    @Test
    fun aCycleFallsBackToPriorityOrder() {
        assertEquals("B", ReactiveRuns.orderNext(listOf("B", "A"), mapOf("A" to setOf("B"), "B" to setOf("A"))))
    }

    // ---------------------------------------------------------------------------------------------
    // Suppliers

    @Test
    fun anAncestorWhoseCodeDeclaresTheNameSuppliesIt() {
        val s = session("suppliers")
        val grand = boxIn(s, "grand", "propUp<Int>(speed)\nspeed = 3")
        val parent = boxIn(s, "parent", "println(1)")
        val child = boxIn(s, "child", "propUp<Int>(speed)\nprintln(speed)")
        grand.connect(parent)
        parent.connect(child)

        assertEquals(listOf("grand"), ReactiveRuns.suppliersFor(child, child, "speed").map { it.id })
        assertTrue(ReactiveRuns.suppliersFor(child, child, "other").isEmpty())
    }

    @Test
    fun theBoxBeingAskedComesFirstAndTheReaderNever() {
        val s = session("asked")
        val a = boxIn(s, "a", "propUp<Int>(q)\nval q0 = named(\"b\").q")
        val b = boxIn(s, "b", "propUp<Int>(q)\nq = 2")
        a.connect(b)
        // a reads b's q: b supplies it; a declares q too, but a is the reader.
        assertEquals(listOf("b"), ReactiveRuns.suppliersFor(asking = b, reader = a, name = "q").map { it.id })
    }

    @Test
    fun aRememberedSupplierIsOfferedAlongsideTheDeclaringAncestors() {
        val s = session("remembered")
        val decl = boxIn(s, "decl", "propUp<Int>(speed)")
        val other = boxIn(s, "other", "speed = 9")
        val child = boxIn(s, "child", "println(speed)")
        decl.connect(child)
        other.connect(child)
        // `other` supplied it before, as its own property.
        other.setPropertyRaw("speed", 9)
        child.running { inheritedPropertyRaw(child, "speed") }
        decl.setPropertyRaw("speed", 1)
        other.removePropertyRaw("speed")

        val suppliers = ReactiveRuns.suppliersFor(child, child, "speed").map { it.id }
        assertEquals(setOf("decl", "other"), suppliers.toSet())
    }

    @Test
    fun deletedBoxesAndTheRootNeverSupply() {
        val s = session("deleted")
        val gone = boxIn(s, "gone", "propUp<Int>(speed)")
        val child = boxIn(s, "child", "println(speed)")
        gone.connect(child)
        s.root.connect(child)
        s.root.setPropertyRaw("code", "propUp<Int>(speed)")
        gone.setPropertyRaw("_tombstone", true)
        assertTrue(ReactiveRuns.suppliersFor(child, child, "speed").isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Changes and pulls

    @Test
    fun onlyAChangingWriteIsAChange() {
        val s = session("changes")
        val a = boxIn(s, "a")
        ReactiveRuns.clearEvents()
        a.running {
            a.reactiveTestDown = 1
            a.reactiveTestDown = 1
            a.reactiveTestDown = 2
        }
        val changes = ReactiveRuns.recentEvents().count { it.contains("changed a.reactiveTestDown") }
        assertEquals(2, changes, ReactiveRuns.recentEvents().joinToString("\n"))
    }

    @Test
    fun aChangeDuringARunWaitsForTheRunToFinish() {
        val s = session("held")
        val a = boxIn(s, "a")
        ReactiveRuns.clearEvents()
        ReactiveRuns.runStarted(a, "run")
        a.running { a.reactiveTestDown = 5 }
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") }, "sent before the run ended")
        ReactiveRuns.runFinished(a, ok = true, mode = "run")
        assertTrue(ReactiveRuns.recentEvents().any { it.contains("changed a.reactiveTestDown") })
    }

    @Test
    fun aFailedRunsChangesAreDropped() {
        val s = session("failed")
        val a = boxIn(s, "a")
        ReactiveRuns.clearEvents()
        ReactiveRuns.runStarted(a, "run")
        a.running { a.reactiveTestDown = 5 }
        ReactiveRuns.runFinished(a, ok = false, mode = "run")
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") })
        assertTrue(ReactiveRuns.recentEvents().any { it.contains("dropped 1 change(s)") })
    }

    @Test
    fun aMissingUpValueWithoutARunloopSaysSo() {
        val s = session("noloop")
        val parent = boxIn(s, "parent", "propUp<Int>(reactiveTestUp)\nreactiveTestUp = 1")
        val child = boxIn(s, "child")
        parent.connect(child)
        if (Runloop.mainThread != null) return // a runloop in this JVM would really run the parent
        val e = assertFailsWith<MissingInheritedPropertyException> { child.running { child.reactiveTestUp } }
        assertTrue(e.message!!.contains("no runloop is running"), e.message)
    }

    @Test
    fun aPresentUpValueIsReadWithoutAPull() {
        val s = session("present")
        val parent = boxIn(s, "parent")
        val child = boxIn(s, "child")
        parent.connect(child)
        parent.setPropertyRaw("reactiveTestUp", 4)
        ReactiveRuns.clearEvents()
        assertEquals(4, child.running { child.reactiveTestUp })
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("pull") })
    }
}
