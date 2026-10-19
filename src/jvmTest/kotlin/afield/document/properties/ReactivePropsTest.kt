package afield.document.properties

import afield.app.diagnostics.named
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.nested
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.app.server.SessionInformation
import afield.app.server.sessions
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.needsResync
import afield.runtime.lifecycle.ReactiveRuns
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.map
import afield.runtime.macros.MacroRegistry
import afield.document.properties.MissingInheritedPropertyException
import afield.document.properties.Reactive
import afield.document.properties.ReactiveInheritance
import afield.document.properties.inheritedPropertyRaw
import afield.document.properties.macro_reactive_prop
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * `syncToProperty` writes with `setPropertyRaw`, not through the delegate. Until 2026-10-14 a
     * slider bound to a `propDown` property therefore changed it without rerunning its readers,
     * although `propDown`'s KDoc promised a slider callback would. The raw write is a change only
     * for a property its box declared Down.
     */
    @Test
    fun aRawWriteToADeclaredDownPropertyIsAChange() {
        val s = session("raw")
        val a = boxIn(s, "a")
        ReactiveRuns.clearEvents()
        ReactiveRuns.noteRawWrite(a, "gain", 0.5, 0.8)
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") }, "undeclared name noted")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        ReactiveRuns.noteRawWrite(a, "gain", 0.8, 0.8)
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") }, "unchanged value noted")
        ReactiveRuns.noteRawWrite(a, "gain", 0.5, 0.8)
        assertTrue(ReactiveRuns.recentEvents().any { it.contains("changed a.gain") }, ReactiveRuns.recentEvents().joinToString("\n"))
    }

    @Test
    fun setPropertyRawOnADeclaredDownPropertyIsAChange() {
        val s = session("raw-set")
        val a = boxIn(s, "a", "propDown<Double>(gain)")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        a.setPropertyRaw("gain", 0.5)
        ReactiveRuns.clearEvents()
        a.setPropertyRaw("gain", 0.5)
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") }, "an unchanged value is not a change")
        a.setPropertyRaw("gain", 0.9)
        assertEquals(1, ReactiveRuns.recentEvents().count { it.contains("changed a.gain") }, ReactiveRuns.recentEvents().joinToString("\n"))
    }

    @Test
    fun aRootPropDownSlotRerunsItsReadersWhileItsDeclarerSaysSo() {
        val s = session("root-down")
        val w = boxIn(s, "w", "rootPropDown<Double>(level)\nroot.level = 1.0")
        w.macro_reactive_root_prop(listOf("<Double>", "(level)"), Reactive.DOWN, "rootPropDown")
        assertTrue(ReactiveRuns.isDeclaredDown(s.root, "level"))
        ReactiveRuns.clearEvents()
        s.root.setPropertyRaw("level", 2.0)
        assertTrue(ReactiveRuns.recentEvents().any { it.contains("changed") && it.contains("level") }, ReactiveRuns.recentEvents().joinToString("\n"))
        w.setPropertyRaw("code", "rootProp<Double>(level)")
        assertFalse(ReactiveRuns.isDeclaredDown(s.root, "level"), "editing the declaration out forgets it")
    }

    @Test
    fun aRootPropUpSlotIsSuppliedByItsDeclarer() {
        val s = session("root-up")
        val supplier = boxIn(s, "supplier", "rootPropUp<Double>(level)\nroot.level = 3.0")
        val reader = boxIn(s, "reader", "println(root.level)")
        assertEquals(listOf(supplier), ReactiveRuns.suppliersFor(s.root, reader, "level"))
    }

    @Test
    fun aChildCompilesAgainstAParentsPropUpBeforeTheParentHasRun() {
        val s = session("gather")
        val parent = boxIn(s, "scale", "propUp<Double>(scale)\nscale = 2.0")
        val child = boxIn(s, "plot", "println(scale)")
        parent.connect(child)
        val gathered = afield.runtime.macros.gatherPreambleUpwards(child)
        assertTrue(gathered.lines().any { "var Box.scale by ReactiveInheritance<Double>(Reactive.UP)" in it && "from:scale" in it }, gathered)
        // The child's own declaration still wins, and is not duplicated by the parent's.
        child.setPropertyRaw("preamble", "var Box.scale by ReactiveInheritance<Double>(Reactive.UP) /**/")
        val again = afield.runtime.macros.gatherPreambleUpwards(child)
        assertEquals(1, again.lines().count { "var Box.scale" in it }, again)
    }

    @Test
    fun aDownDeclarationEditedOutOfTheCodeIsForgotten() {
        val s = session("edited")
        val a = boxIn(s, "a", "propDown<Double>(gain)\ngain = 1.0")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        assertTrue(ReactiveRuns.isDeclaredDown(a, "gain"))
        a.setPropertyRaw("code", "prop<Double>(gain)\ngain = 1.0")
        ReactiveRuns.clearEvents()
        ReactiveRuns.noteRawWrite(a, "gain", 0.5, 0.8)
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") }, ReactiveRuns.recentEvents().joinToString("\n"))
        assertFalse(ReactiveRuns.isDeclaredDown(a, "gain"))
    }

    /**
     * Kotlin `propDown` always carries its type: without one the macro refuses to expand, so
     * the code check in [ReactiveRuns.isDeclaredDown] only needs the `<T>(name...)` form, with
     * any spacing and an optional default.
     */
    @Test
    fun aDownDeclarationNeedsItsTypeAndToleratesSpacingAndDefaults() {
        val s = session("forms")
        val a = boxIn(s, "a", "propDown(gain)")
        assertFailsWith<MacroRegistry.MacroExpansionProblem> {
            a.macro_reactive_prop(listOf("(gain)"), Reactive.DOWN, "propDown")
        }
        assertFalse(ReactiveRuns.isDeclaredDown(a, "gain"))
        val b = boxIn(s, "b", "propDown< Map<String, Double> >( gain , mapOf() )")
        b.macro_reactive_prop(listOf("<Map<String, Double>>", "(gain, mapOf())"), Reactive.DOWN, "propDown")
        assertTrue(ReactiveRuns.isDeclaredDown(b, "gain"))
        b.setPropertyRaw("code", "propDown<Double>(gainTrim)")
        assertFalse(ReactiveRuns.isDeclaredDown(b, "gain"), "a longer name is not the same declaration")
    }

    @Test
    fun aDeletedBoxsDownDeclarationIsForgotten() {
        val s = session("deleted")
        val a = boxIn(s, "a", "propUpDown<Double>(gain)")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.UP_DOWN, "propUpDown")
        assertTrue(ReactiveRuns.isDeclaredDown(a, "gain"))
        a.setPropertyRaw("_tombstone", true)
        ReactiveRuns.clearEvents()
        ReactiveRuns.noteRawWrite(a, "gain", 0.5, 0.8)
        assertTrue(ReactiveRuns.recentEvents().none { it.contains("changed") })
        val b = boxIn(s, "b", "propDown<Double>(gain)")
        b.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        ReactiveRuns.forgetDown(b)
        assertFalse(ReactiveRuns.isDeclaredDown(b, "gain"))
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

    // ---------------------------------------------------------------------------------------------
    // Declarations the code no longer makes (nightly 2026-10-18)

    /** A declaration commented out is not a declaration: the macro does not expand it. */
    @Test
    fun aCommentedOutDownDeclarationIsForgotten() {
        val s = session("commented")
        val a = boxIn(s, "a", "propDown<Double>(gain)\ngain = 1.0")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        a.setPropertyRaw("code", "// propDown<Double>(gain)\ngain = 1.0")
        assertFalse(ReactiveRuns.isDeclaredDown(a, "gain"), "a line comment")
        val b = boxIn(s, "b", "propDown<Double>(gain)")
        b.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        b.setPropertyRaw("code", "/* propDown<Double>(gain) /* nested */ still a comment */\nval url = \"http://x\"")
        assertFalse(ReactiveRuns.isDeclaredDown(b, "gain"), "a nested block comment")
        val c = boxIn(s, "c", "val u = \"http://x\"; propDown<Double>(gain)")
        c.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        assertTrue(ReactiveRuns.isDeclaredDown(c, "gain"), "// inside a string does not start a comment")
    }

    @Test
    fun aDownDeclarationIsAWholeWord() {
        val s = session("word")
        val a = boxIn(s, "a", "propDown<Double>(gain)")
        a.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        a.setPropertyRaw("code", "mypropDown<Double>(gain)")
        assertFalse(ReactiveRuns.isDeclaredDown(a, "gain"), "mypropDown is not propDown")
        val b = boxIn(s, "b", "propDown<Double>(gain)")
        b.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        b.setPropertyRaw("code", "propUDown<Double>(gain)")
        assertFalse(ReactiveRuns.isDeclaredDown(b, "gain"), "propUDown is no macro")
    }

    /** `propUp<T>(name, default)` declares [name] as much as `propUp<T>(name)` does. */
    @Test
    fun anAncestorDeclaringWithADefaultSuppliesIt() {
        val s = session("default-supplier")
        val parent = boxIn(s, "parent", "propUp<Double>(speed, 1.0)\nspeed = 3.0")
        val child = boxIn(s, "child", "println(1)")
        parent.connect(child)
        assertEquals(listOf("parent"), ReactiveRuns.suppliersFor(child, child, "speed").map { it.id })
    }

    @Test
    fun aCommentedOutUpDeclarationSuppliesNothing() {
        val s = session("commented-supplier")
        val parent = boxIn(s, "parent", "// propUp<Double>(speed)\nprintln(2)")
        val child = boxIn(s, "child", "println(1)")
        parent.connect(child)
        assertTrue(ReactiveRuns.suppliersFor(child, child, "speed").isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Two open documents holding the same uid (a copied document) (nightly 2026-10-18)

    @Test
    fun aDownDeclarationBelongsToItsDocument() {
        val one = session("doc-one")
        val two = session("doc-two")
        val a1 = boxIn(one, "a", "propDown<Double>(gain)")
        a1.macro_reactive_prop(listOf("<Double>", "(gain)"), Reactive.DOWN, "propDown")
        val a2 = boxIn(two, "a", "println(1)")
        assertFalse(ReactiveRuns.isDeclaredDown(a2, "gain"), "the copy never declared it")
        assertTrue(ReactiveRuns.isDeclaredDown(a1, "gain"), "asking the copy must not forget the original's declaration")
        ReactiveRuns.forgetDown(a2)
        assertTrue(ReactiveRuns.isDeclaredDown(a1, "gain"), "deleting the copy must not forget the original's declaration")
    }

    @Test
    fun aRunInAnotherDocumentDoesNotMakeAReaderUpToDate() {
        val one = session("run-one")
        val two = session("run-two")
        val h1 = boxIn(one, "h")
        val r1 = boxIn(one, "r")
        val r2 = boxIn(two, "r")
        PropertyDependencies.recordNamedRead(r1, h1, "gain")
        val change = ReactiveRuns.Change(h1, "gain", PropertyDependencies.clock.incrementAndGet(), null)
        assertEquals(listOf(r1), ReactiveRuns.Downstream.dirtyReaders(listOf(change)).keys.toList())
        ReactiveRuns.runStarted(r2, "run")
        ReactiveRuns.runFinished(r2, ok = true, mode = "run")
        assertEquals(listOf(r1), ReactiveRuns.Downstream.dirtyReaders(listOf(change)).keys.toList(),
            "the other document's 'r' ran, not this one's")
    }

    /** A deleted box's run records go with it, rather than pinning the Box for the JVM's life. */
    @Test
    fun deletingABoxForgetsItsRunRecords() {
        val s = session("leak")
        val a = boxIn(s, "a")
        ReactiveRuns.runStarted(a, "run")
        ReactiveRuns.runFinished(a, ok = true, mode = "run")
        assertTrue(ReactiveRuns.remembers(a))
        ReactiveRuns.forgetDown(a)
        assertFalse(ReactiveRuns.remembers(a))
    }

    /** Closing a document (its session cleanup) leaves no reactive state or dependency record behind. */
    @Test
    fun closingADocumentForgetsItsReactiveState() {
        val s = session("close")
        val h = boxIn(s, "h")
        val r = boxIn(s, "r")
        PropertyDependencies.recordNamedRead(r, h, "gain")
        ReactiveRuns.declaredDown(h, "gain")
        ReactiveRuns.runStarted(r, "run")
        ReactiveRuns.runFinished(r, ok = true, mode = "run")
        assertTrue(ReactiveRuns.remembers(r))
        assertTrue(PropertyDependencies.holds(s.filename))
        assertTrue(ReactiveRuns.recordCountFor(s.filename) > 0)
        s.cleanup()
        assertFalse(ReactiveRuns.remembers(r), "run record survived the close")
        assertEquals(0, ReactiveRuns.recordCountFor(s.filename))
        assertFalse(PropertyDependencies.holds(s.filename), "dependency record survived the close")
    }
}
