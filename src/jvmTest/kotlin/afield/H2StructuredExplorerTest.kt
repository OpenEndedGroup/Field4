package afield

import afield.graphics.FLine
import afield.server.Browsable
import afield.server.h2ExplorerCellsForTesting
import afield.stdlib.Functions
import afield.stdlib.Quantity
import afield.stdlib.withUnit
import eu.sirotin.kotunil.base.mm
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.h2` on a domain object is a browsable tree, not a copy of `.h`.
 *
 * Until `H2Reflect.kt`, `LazyValueVisualizer.visualize` opened with
 * `if (adapted == null && !isHeavyStructure(value)) return ValueVisualizer.visualize(...)`, and the
 * gate admitted six collection shapes. So `.h2` on anything that was neither a registered adapter
 * type nor one of those shapes was byte-for-byte `.h`. [oldGateAdmits] restates that predicate
 * verbatim, which lets the tests below assert the before/after difference as a *number* rather than
 * as a claim.
 *
 * A domain object failed all three of `.h2`'s surfaces, and only fixing the gate would have opened
 * an empty frame — so the tests come in pairs: the gate, and then whether each of `/tree`/`/browse`
 * (the `Browsable` from `wrapForInspector`) and the embedded `/h2-explorer` iframe (the `H2Source`
 * behind `h2ExplorerCellsForTesting`) actually has content behind it.
 */
class H2StructuredExplorerTest {

    // -- fixtures ---------------------------------------------------------------------------------

    /** A user-shaped data class: what someone writes in a box and then types `.h2` on. */
    private data class Particle(val id: Int, val x: Double, val y: Double, val label: String)

    /** Deliberately declared out of alphabetical order, to pin constructor-order enumeration. */
    private data class Zed(val zulu: Int, val alpha: Int, val mike: Int)

    /** A property that throws. The brief asked that this be visible rather than silent. */
    private class Fragile(val ok: Int) {
        val boom: String get() = throw IllegalStateException("no value here")
    }

    /** A self-referential object graph: the tree must terminate. */
    private class Loop(val name: String) {
        var next: Loop? = null
        var tag: Int = 0
    }

    /** Deeper than the click floor, so the depth cap has something to stop. */
    private class Nest(val depth: Int, val label: String) {
        var inner: Nest? = null
    }

    /**
     * One property, so below the tree floor.
     *
     * A *nested* class, not a local one declared inside the test function: Kotlin reflection throws
     * on local classes, `H2Reflect` catches that and reports zero properties, and the assertion
     * would then pass for the wrong reason — a tautology dressed as a boundary test.
     */
    private class OneProperty(val only: Int)

    /**
     * `isHeavyStructure` exactly as it was at `693b6b717`, before this change.
     *
     * Restated rather than referenced on purpose: it is a nine-line pure predicate, so a copy is
     * exact, and having it here turns "domain objects used to fall through to `.h`" from a sentence
     * in a commit message into an assertion that runs.
     */
    private fun oldGateAdmits(value: Any?): Boolean =
        value is Map<*, *> ||
            value is List<*> ||
            value is Array<*> ||
            value is IntArray ||
            value is LongArray ||
            value is FloatArray ||
            value is DoubleArray ||
            value is java.nio.FloatBuffer ||
            value is java.nio.IntBuffer

    /** True when `.h2` rendered the explorer widget rather than delegating to `.h`. */
    private fun engagesH2(value: Any?): Boolean = "value-viz-h2" in LazyValueVisualizer.visualize(value)

    /**
     * The `/tree` and `/browse` children of [value], or null when it has no tree behind it.
     *
     * **Adapts first**, because that is what production does: `LazyValueVisualizer.visualize`
     * computes `rawValue = adapted?.value ?: value` and wraps *that*. An earlier draft of this
     * helper wrapped the raw value, which quietly exercised a path production never takes — it
     * reflected a `Box` instead of using the `Box` adapter, and so read `Box.ki`. The Throwable
     * sample is what exposed it: adapter-served, and therefore childless when wrapped raw.
     */
    private fun treeChildrenOf(value: Any?): Map<String, Any?>? {
        val raw = value?.let { h2AdaptedValue(it) } ?: value
        return (wrapForInspector(raw, title = "value") as? Browsable)?.dir()
    }

    /**
     * The embedded `/h2-explorer` iframe's columns and first rows for [value], adapting first for
     * the same reason [treeChildrenOf] does: `visualize` retains `rawValue` under the token, so the
     * socket serves the adapted value, not the original.
     */
    private fun explorerCellsOf(value: Any?, count: Int = 4): Pair<List<String>, List<List<String>>> {
        val raw = value?.let { h2AdaptedValue(it) } ?: value
        return h2ExplorerCellsForTesting(raw, start = 0, count = count)
    }

    // -- the premise ------------------------------------------------------------------------------

    /**
     * Assert the premise before anything else, so an environment change fails HERE loudly rather
     * than quietly turning every assertion below into a tautology.
     */
    @Test
    fun `the premise - the old gate refused every domain sample, and h2 was then verbatim h`() {
        val domainSamples = domainSampleSet()
        assertTrue(domainSamples.size >= 8, "too few samples to say anything; got ${domainSamples.size}")

        val admittedByOldGate = domainSamples.filter { oldGateAdmits(it.second) }
        assertTrue(
            admittedByOldGate.isEmpty(),
            "these samples were already collections, so they prove nothing about the change: " +
                admittedByOldGate.map { it.first }
        )

        // And the delegation is still exactly delegation for a value the new gate also refuses:
        // identical bytes, not merely similar. This is the mechanism the whole brief rests on.
        val refused = 42
        assertFalse(engagesH2(refused), "an Int must not open an explorer")
        assertEquals(
            ValueVisualizer.visualize(refused),
            LazyValueVisualizer.visualize(refused),
            "for a refused value `.h2` must be byte-for-byte `.h`"
        )
    }

    /**
     * The number, with a floor near it.
     *
     * Population: the [domainSampleSet] below — 11 named Field/user domain values, none of which is
     * a collection (asserted above). Before this change 0 of 11 engaged `.h2`. The floor is 11 of
     * 11, i.e. all of them, because a floor loose enough to pass with half the set certifies
     * nothing.
     */
    @Test
    fun `every domain sample now opens a real explorer on both surfaces`() {
        registerH2DomainAdapters()
        val samples = domainSampleSet()

        val failures = samples.mapNotNull { (name, value) ->
            val gate = engagesH2(value)
            val tree = treeChildrenOf(value)?.takeIf { it.isNotEmpty() }
            val (columns, rows) = explorerCellsOf(value)
            // A `ScalarSource` — one column named "value", one row — is the shape a non-collection
            // used to get, and is indistinguishable from an empty frame to a person.
            val iframe = !(columns == listOf("value") && rows.size <= 1)
            when {
                !gate -> "$name: gate refused it"
                tree == null -> "$name: /tree and /browse have no children behind them"
                !iframe -> "$name: the iframe is still a single toString() row (columns=$columns)"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), "${failures.size} of ${samples.size} samples are not browsable: $failures")
        assertEquals(11, samples.size, "the stated population is 11; update the floor if you change the set")
    }

    /**
     * 11 domain values, none a collection: four served by hand-written adapters and seven by the
     * reflective default. Kept as one list so the count in the test above is checkable by eye.
     */
    private fun domainSampleSet(): List<Pair<String, Any>> = listOf(
        // reflective default
        "user data class" to Particle(1, 0.5, 0.25, "p1"),
        "constructor-order data class" to Zed(1, 2, 3),
        "Functions.Domain" to Functions.Domain(0.0, 1.0),
        "Rect" to Rect(0.0, 0.0, 10.0, 20.0),
        "RunningState" to RunningState(7, "default"),
        "CachedLaunchReturn" to CachedLaunchReturn("1+1", 2),
        "a class with a throwing property" to Fragile(3),
        // hand-written adapters
        "Box" to Box("h2-test-box"),
        "Functions.Fn" to Functions.fn(Functions.Domain(0.0, 1.0), "sin") { sin(it) },
        "FLine" to FLine().moveTo(0.0, 0.0).lineTo(1.0, 0.0).lineTo(1.0, 1.0),
        "Throwable" to IllegalArgumentException("bad", IllegalStateException("root cause"))
    )

    // -- what `.h2` must keep refusing --------------------------------------------------------

    /**
     * The fence. `UnitValueVisualizerTest` already pins the `Quantity` case; this widens it to the
     * whole [readsWellInline] list, because a `.h2` that hijacks a matrix or a vector is a
     * regression dressed as a feature.
     */
    @Test
    fun `values whose compact rendering is the value stay with the compact renderer`() {
        val mustNotEngage: List<Pair<String, Any>> = listOf(
            "Int" to 42,
            "Double" to 1.5,
            "String" to "hello",
            "Boolean" to true,
            "Char" to 'x',
            "enum" to Functions.EdgeMode.CLAMP,
            "Pair" to (1 to 2),
            "Triple" to Triple(1, 2, 3),
            "lambda" to { x: Int -> x + 1 },
            "Vec3" to afield.stdlib.linalg.Vector3f(1f, 2f, 3f),
            "Matrix4f" to afield.stdlib.linalg.Matrix4f(),
            "KClass" to String::class,
            // Platform types: the narrowing that keeps "everything" from meaning "the whole heap".
            "java.io.File" to File("/tmp"),
            "Thread" to Thread.currentThread()
        )

        val hijacked = mustNotEngage.filter { engagesH2(it.second) }
        assertTrue(hijacked.isEmpty(), "`.h2` must leave these to `.h`: ${hijacked.map { it.first }}")
    }

    @Test
    fun `a Quantity still renders through the unit aware compact path`() {
        // Duplicated deliberately from UnitValueVisualizerTest: that file guards the unit renderer,
        // this one guards the gate, and the two reasons drift apart.
        val q: Quantity = 7.withUnit(mm)
        assertFalse(engagesH2(q))
    }

    @Test
    fun `an object with fewer than two properties has no tree and stays with h`() {
        // The floor is "is there a tree at all", not a size heuristic — see H2_REFLECT_MIN_PROPERTIES.
        // Premise: reflection can see this class at all, so the refusal is the floor and not a
        // reflection failure.
        assertEquals(1, H2Reflect.readablePropertyCount(OneProperty(1)), "premise: exactly one property")
        assertFalse(engagesH2(OneProperty(1)), "a single-property wrapper reads better inline")
    }

    // -- the reflective default's traps ----------------------------------------------------------

    @Test
    fun `properties are enumerated in constructor order, then by name`() {
        val children = assertNotNull(treeChildrenOf(Zed(1, 2, 3)))
        assertEquals(
            listOf("zulu", "alpha", "mike"),
            children.keys.toList(),
            "constructor order is what the person typed; `memberProperties` order is not contractual"
        )
    }

    @Test
    fun `a property whose getter throws is visible, marked, and carries the exception`() {
        val children = assertNotNull(treeChildrenOf(Fragile(3)))

        val marked = children.keys.filter { it.startsWith(H2Property.FAILURE_MARK) }
        assertEquals(listOf("${H2Property.FAILURE_MARK} boom"), marked, "the failure must be labelled, not dropped")
        assertTrue("ok" in children.keys, "one bad getter must not lose the good properties")

        // And the reason must reach the person, on the iframe surface too.
        val (columns, rows) = explorerCellsOf(Fragile(3), count = 8)
        assertEquals(listOf("value", "type"), columns)
        val flat = rows.flatten()
        assertTrue(
            flat.any { "getter threw" in it && "no value here" in it },
            "the exception text is the whole point of showing the row; got $flat"
        )
    }

    @Test
    fun `a self-referential object renders a circular reference leaf instead of recursing forever`() {
        val a = Loop("a")
        val b = Loop("b")
        a.next = b
        b.next = a

        // Walk the `next` chain as far as the tree will let us. Without the identity path guard this
        // loops until the depth cap; with it, it stops at the first repeat.
        var node = wrapForInspector(a, title = "value") as? Browsable
        val headings = ArrayList<String>()
        var steps = 0
        while (node != null && steps < 40) {
            headings.add(node.heading())
            val next = node.dir()?.get("next") as? Browsable ?: break
            node = next
            steps++
        }

        assertTrue(steps < 40, "the walk did not terminate: $headings")
        assertTrue(
            headings.any { "[circular reference]" in it },
            "the loop must be named where it closes; walked $headings"
        )
    }

    @Test
    fun `the tree bottoms out rather than descending forever`() {
        // 20 levels deep against a click floor of 6.
        val deepest = Nest(0, "n0")
        var head = deepest
        for (i in 1..20) head = Nest(i, "n$i").also { it.inner = head }

        var node = wrapForInspector(head, title = "value") as? Browsable
        var depth = 0
        while (node != null && depth < 50) {
            val next = node.dir()?.get("inner") as? Browsable ?: break
            node = next
            depth++
        }
        assertTrue(depth in 1..10, "expected a floor somewhere under 10 levels of clicking, reached $depth")
    }

    // -- the isHeavyStructure settle -------------------------------------------------------------

    /**
     * The gate used to be strictly narrower than its own machinery: it admitted `IntArray` but not
     * `ByteArray`, `List` but not `Set`, while `wrapForInspector` and `makeSource` already drew all
     * of them. Each sample below is one the old gate refused *and* both surfaces could already
     * render — so this asserts the omission, not a new feature.
     */
    @Test
    fun `collections the machinery already drew are now admitted by the gate`() {
        val samples: List<Pair<String, Any>> = listOf(
            "Set" to setOf(1, 2, 3, 4, 5),
            // A `Collection` that is neither a `List` nor a `Set` — the case the new `Collection<*>`
            // arm exists for. NOT `kotlin.collections.ArrayDeque`, which extends
            // `AbstractMutableList` and so was admitted by the old gate all along; the premise
            // assertion below caught that mistake in the first draft of this test.
            "PriorityQueue" to java.util.PriorityQueue(listOf(5, 3, 1, 4, 2)),
            "ByteArray" to ByteArray(40) { it.toByte() },
            "ShortArray" to ShortArray(40) { it.toShort() },
            "BooleanArray" to BooleanArray(40) { it % 2 == 0 },
            "CharArray" to CharArray(40) { 'a' + (it % 26) }
        )

        // Premise: every one of these was refused before.
        val alreadyAdmitted = samples.filter { oldGateAdmits(it.second) }
        assertTrue(alreadyAdmitted.isEmpty(), "these were not omissions: ${alreadyAdmitted.map { it.first }}")

        val broken = samples.mapNotNull { (name, value) ->
            val (columns, rows) = explorerCellsOf(value)
            when {
                !engagesH2(value) -> "$name: gate still refuses it"
                treeChildrenOf(value) == null -> "$name: no /tree children"
                columns == listOf("value") && rows.size <= 1 -> "$name: iframe is a single scalar row"
                else -> null
            }
        }
        assertTrue(broken.isEmpty(), "still not browsable: $broken")
    }

    // -- the hand-written adapters ---------------------------------------------------------------

    /**
     * The `Box` adapter must not build a REPL engine.
     *
     * `Box.ki` is a `lazyVar` that compiles a K2 engine on read, which is the concrete case behind
     * the side-effect limit `H2Reflect` admits it cannot detect — and the reason `Box` is served by
     * an adapter rather than by reflection. If someone deletes the adapter and lets the reflective
     * default take `Box`, this test is what says so.
     */
    @Test
    fun `exploring a Box does not build its Kotlin engine`() {
        registerH2DomainAdapters()
        val box = Box("h2-no-engine")
        assertNull(box.liveKi, "premise: a fresh box has no engine")

        // Premise, and the real assertion: an adapter must *substitute* something for the box. If
        // the adapter is deleted, `h2AdaptedValue` returns the box itself, `wrapForInspector` hands
        // it to the reflective default, and `Box.ki` gets read. Identity is the check that survives
        // someone "simplifying" this file.
        assertTrue(
            h2AdaptedValue(box) !== box,
            "Box must be adapter-served, not reflected — reflection reads `Box.ki`, which compiles"
        )

        val html = LazyValueVisualizer.visualize(box)
        explorerCellsOf(box, count = 64)
        treeChildrenOf(box)

        assertTrue("value-viz-h2" in html, "premise: the Box adapter engaged at all")
        assertNull(box.liveKi, "exploring a box must not compile one")
    }

    @Test
    fun `a Box explores as its identity, properties and edges`() {
        registerH2DomainAdapters()
        val parent = Box("h2-parent").also { it.name = "container" }
        val box = Box("h2-child").also { it.name = "worker" }
        box.parents.add(parent)
        parent.children.add(box)
        box.properties["myProp"] = 17

        val children = assertNotNull(treeChildrenOf(box))
        val keys = children.keys.map { it.substringAfter("] ", it) }
        listOf("name", "id", "properties", "edges", "engine").forEach { expected ->
            assertTrue(keys.any { expected in it }, "expected a `$expected` row; got $keys")
        }

        // The edges table is the `parents`/`children` pair `398cb0ca9` made `nestInto` create.
        val (columns, rows) = explorerCellsOf(boxEdgeRows(box), count = 8)
        assertEquals(listOf("edge", "name", "id"), columns, "edges render as a table, not a summary")
        assertTrue(rows.any { it.contains("parent") && it.contains("container") }, "got $rows")
    }

    @Test
    fun `an Fn explores as a sampled x, f(x) table with the right values`() {
        registerH2DomainAdapters()
        val fn = Functions.fn(Functions.Domain(0.0, 1.0), "sin") { sin(it) }

        val (columns, rows) = explorerCellsOf(fn, count = 3)
        assertEquals(listOf("x", "f(x)"), columns, "a function's content is its values, not its fields")
        assertTrue(rows.size == 3, "expected 3 sampled rows, got ${rows.size}")

        // x = 0 exactly, so sin(0) = 0. A table of the right shape carrying the wrong numbers is
        // the failure this pins.
        assertEquals("0", rows[0][0], "first sample is the domain minimum")
        assertTrue(abs(rows[0][1].toDouble()) < 1e-9, "sin(0) should be 0, got ${rows[0][1]}")
        val x1 = rows[1][0].toDouble()
        assertTrue(abs(rows[1][1].toDouble() - sin(x1)) < 1e-5, "row 1 must be sin(${x1})")
    }

    @Test
    fun `an FLine explores as its node table, and a Close node does not throw`() {
        registerH2DomainAdapters()
        // `closed()` appends a `Close`, which is Node(0) and whose `end` throws — the trap the
        // adapter avoids by reading `storage.lastOrNull()`.
        val line = FLine().moveTo(0.0, 0.0).lineTo(1.0, 0.0).lineTo(1.0, 1.0)

        val (columns, rows) = explorerCellsOf(line, count = 8)
        assertEquals(listOf("i", "kind", "points", "x", "y", "z", "props"), columns)
        assertEquals(3, rows.size)
        assertTrue(rows[0][1] == "MoveTo", "first node is the moveTo; got ${rows[0][1]}")
        assertTrue(rows[1][1] == "LineTo", "got ${rows[1][1]}")
    }

    @Test
    fun `a Throwable explores as message, cause chain and stack`() {
        registerH2DomainAdapters()
        val t = IllegalArgumentException("outer", IllegalStateException("inner"))

        val children = assertNotNull(treeChildrenOf(t))
        val keys = children.keys.map { it.substringAfter("] ", it) }
        listOf("type", "message", "causes", "stack").forEach { expected ->
            assertTrue(keys.any { expected in it }, "expected a `$expected` row; got $keys")
        }
        assertTrue("value-viz-h2" in LazyValueVisualizer.visualize(t), "an exception should be explorable")
    }
}
