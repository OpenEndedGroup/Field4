package afield.graphquery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the generic query core: predicates, typed lenses, compiled patterns and the
 * fast literal-prefix resolver.
 *
 * Living in `afield.graphquery` is deliberate — this file imports nothing from
 * `afield.four.synth`, so the fact that it compiles is itself the assertion that the core
 * carries no synth (and no runloop) dependency.
 */
class GraphQueryCoreTest {

    enum class Mode { OFF, ON }

    class Osc(var freq: Float, var gain: Float, var label: String, var enabled: Boolean, var mode: Mode)

    class Voice(val carrier: Osc, val modulator: Osc, var name: String)

    class Nested(val inner: Voice, var depth: Int)

    /** A node holding a reference back up the graph, for the cycle/alias edge cases. */
    class Backref(var label: String) {
        var up: Any? = null
    }

    private fun osc(freq: Float = 220f, gain: Float = 0.5f) =
        Osc(freq, gain, "osc", true, Mode.ON)

    private fun voice() = Voice(osc(220f, 0.8f), osc(440f, 0.2f), "voice")

    // ── predicates: the XPath `[...]` half ──────────────────────────────────

    @Test
    fun whereFiltersOnTheWholeRef() {
        val v = voice()
        val writableFloats = v.graphPaths("**").where { it.writable && it.get() is Float }

        assertEquals(
            listOf("carrier/freq", "carrier/gain", "modulator/freq", "modulator/gain"),
            writableFloats.paths()
        )
    }

    @Test
    fun whereValueFiltersOnValue() {
        val v = voice()
        val loud = v.graphPaths("**/gain").whereValue { (it as? Float ?: 0f) > 0.5f }

        assertEquals(listOf("carrier/gain"), loud.paths())
    }

    @Test
    fun whereValueIsCombinesTypeAndPredicate() {
        val v = voice()

        // Only Floats are considered, so the String and Boolean leaves cannot throw here.
        val high = v.graphPaths("**").whereValueIs<Float> { it > 300f }
        assertEquals(listOf("modulator/freq"), high.paths())

        val on = v.graphPaths("**").whereValueIs<Boolean> { it }
        assertEquals(listOf("carrier/enabled", "modulator/enabled"), on.paths())
    }

    @Test
    fun ownedByFiltersByIdentity() {
        val v = voice()
        val mine = v.graphPaths("**").ownedBy(v.carrier)

        assertTrue(mine.isNotEmpty)
        assertTrue(mine.all { it.owner === v.carrier })
        assertTrue(mine.paths().all { it.startsWith("carrier/") }, "${mine.paths()}")
    }

    @Test
    fun nonNullDropsNullValuedSlots() {
        class Maybe(var a: Float?, var b: Float?)

        val set = Maybe(1f, null).graphPaths("**")
        assertEquals(listOf("a", "b"), set.paths())
        assertEquals(listOf("a"), set.nonNull().paths())
    }

    @Test
    fun atDepthRestrictsByPathLength() {
        val n = Nested(voice(), 3)

        assertEquals(listOf("inner", "depth"), n.graphPaths("**").atDepth(1..1).paths())
        assertTrue(n.graphPaths("**").atDepth(3..3).paths().all { it.count { c -> c == '/' } == 2 })
    }

    @Test
    fun excludingIsTheComplementOfMatching() {
        val v = voice()
        val all = v.graphPaths("**")
        val without = all.excluding("**/gain")

        assertTrue(without.paths().none { it.endsWith("/gain") })
        assertEquals(all.size, without.size + all.matching("**/gain").size)
    }

    @Test
    fun predicatesCompose() {
        val v = voice()
        val result = v.graphPaths("**")
            .matching("**/freq")
            .whereValueIs<Float> { it > 100f }
            .writable()

        assertEquals(listOf("carrier/freq", "modulator/freq"), result.paths())
    }

    @Test
    fun getLooksUpAnExactPath() {
        val v = voice()
        val set = v.graphPaths("**")

        assertNotNull(set["carrier/freq"])
        assertEquals(220f, set["carrier/freq"]!!.get())
        assertNull(set["carrier/nope"])
    }

    // ── typed lenses (the general terminal) ─────────────────────────────────

    @Test
    fun lensesSelectOnlyMatchingTypes() {
        val v = voice()

        assertEquals(
            listOf("carrier/label", "modulator/label", "name"),
            v.graphPaths("**").lenses<String>().paths().sorted()
        )
        assertEquals(
            listOf("carrier/enabled", "modulator/enabled"),
            v.graphPaths("**").lenses<Boolean>().paths()
        )
    }

    @Test
    fun lensGetIsTypedAndSetWritesThrough() {
        val v = voice()
        val lens = v.graphPaths("carrier/label").lenses<String>().firstOrNull()!!

        assertEquals("osc", lens.get())
        lens.set("bass")
        assertEquals("bass", v.carrier.label)
    }

    @Test
    fun lensSetAssignsEveryWritableLens() {
        val v = voice()
        val written = v.graphPaths("**").lenses<String>().set("x")

        assertEquals(3, written)
        assertEquals("x", v.carrier.label)
        assertEquals("x", v.modulator.label)
        assertEquals("x", v.name)
    }

    @Test
    fun lensUpdateReadsModifiesWrites() {
        val v = voice()
        v.graphPaths("**").lenses<Boolean>().update { !it }

        assertFalse(v.carrier.enabled)
        assertFalse(v.modulator.enabled)
    }

    @Test
    fun lensUpdateIndexedSeesPosition() {
        val v = voice()
        v.graphPaths("**").lenses<String>().updateIndexed { index, value -> "$value$index" }

        assertEquals(listOf("osc0", "osc1", "voice2"), v.graphPaths("**").lenses<String>().values())
    }

    @Test
    fun lensesHandleEnumsAndNumbersAlike() {
        val v = voice()

        val modes = v.graphPaths("**").lenses<Mode>()
        assertEquals(2, modes.size)
        modes.set(Mode.OFF)
        assertEquals(Mode.OFF, v.carrier.mode)

        val floats = v.graphPaths("**/freq").lenses<Float>()
        floats.update { it * 2f }
        assertEquals(440f, v.carrier.freq, 0f)
    }

    @Test
    fun lensSetPositionalAssignmentZipsAndStopsShort() {
        val v = voice()
        val written = v.graphPaths("**").lenses<String>().set(listOf("a", "b"))

        assertEquals(2, written)
        assertEquals("a", v.carrier.label)
        assertEquals("b", v.modulator.label)
        assertEquals("voice", v.name, "third lens untouched")
    }

    @Test
    fun readOnlyLensesReportAndRefuse() {
        class Fixed(val label: String)

        val lens = Fixed("x").graphPaths("label").lenses<String>().firstOrNull()!!
        assertFalse(lens.writable)
        assertFalse(lens.trySet("y"), "trySet reports refusal rather than throwing")
        assertEquals(0, Fixed("x").graphPaths("**").lenses<String>().writable().size)
    }

    @Test
    fun graphLensesEntryPointCombinesQueryAndTyping() {
        val v = voice()
        assertEquals(listOf("carrier/enabled", "modulator/enabled"), v.graphLenses<Boolean>("**").paths())
    }

    @Test
    fun lensSetRendersAsHtml() {
        val html = voice().graphPaths("**").lenses<String>().toHTML()

        assertTrue(html.contains("LensSet"), html)
        assertTrue(html.contains("carrier/label"), html)
        assertTrue(html.contains("<table"), html)
    }

    // ── compiled patterns ───────────────────────────────────────────────────

    @Test
    fun compiledPatternsAreCachedAndReusable() {
        val a = GraphQuery.compile("**/freq")
        val b = GraphQuery.compile("**/freq")

        assertSame(a, b, "identical patterns share one parsed instance")
        assertEquals("**/freq", a.pattern)
    }

    @Test
    fun isStaticIdentifiesWildcardFreePatterns() {
        assertTrue(GraphQuery.compile("carrier/freq").isStatic)
        assertFalse(GraphQuery.compile("carrier/" + "*").isStatic)
        assertFalse(GraphQuery.compile("**").isStatic)
    }

    @Test
    fun compiledPatternResolvesRepeatedly() {
        val plan = GraphQuery.compile("**/freq")
        val v = voice()

        assertEquals(listOf("carrier/freq", "modulator/freq"), plan.resolve(v).paths())
        v.carrier.freq = 1f
        assertEquals(listOf(1f, 440f), plan.resolve(v).values())
    }

    @Test
    fun patternMatchesTestsASinglePath() {
        val plan = GraphQuery.compile("**/freq")

        assertTrue(plan.matches("carrier/freq"))
        assertTrue(plan.matches("freq"))
        assertFalse(plan.matches("carrier/gain"))
    }

    // ── the fast path must agree with the full walk ──────────────────────────

    /**
     * The load-bearing test for the literal-prefix fast path: for every pattern shape, the
     * optimized resolver must return exactly what a full traversal plus glob filter returns.
     * Uses the internal [GraphWalker] directly to force the slow path.
     */
    @Test
    fun fastPathAgreesWithFullWalk() {
        val roots: List<Pair<String, Any>> = listOf(
            "voice" to voice(),
            "nested" to Nested(voice(), 2),
            "map" to mutableMapOf<String, Any?>("a" to osc(), "b" to osc(), "n" to null),
            "list" to mutableListOf<Any?>(osc(), osc()),
            "array" to arrayOf<Any?>(osc(), osc()),
            // Two keys differing only in case. Matching ignores case, so one pattern names
            // both slots — and the literal fast path, which can only report one, has to
            // notice and stand down. A map is the fixture because map keys collide without
            // any help from Kotlin naming rules.
            "caseTwins" to mutableMapOf<String, Any?>("freq" to 1f, "Freq" to 2f, "gain" to osc())
        )
        val patterns = listOf(
            "**",
            "*",
            "**/freq",
            "*/freq",
            "carrier",
            "carrier/freq",
            "carrier/" + "*",
            "carrier/" + "**",
            "inner/carrier/freq",
            "inner/" + "*" + "/freq",
            "inner/" + "**",
            "a/freq",
            "a/" + "*",
            "0/freq",
            "n",
            "nope",
            "carrier/nope",
            "**/nope",
            // Case: right, wrong, and colliding.
            "CARRIER/freq",
            "Carrier",
            "FREQ",
            "freq",
            "**/FREQ",
            "GAIN/" + "*",
            // Globs inside one segment.
            "*req",
            "car*",
            "ca*er",
            "**/" + "*req",
            "carrier/g*",
            "*" + "/*ain",
            "no*such*thing",
            // Alternation, including overlapping and lopsided branches.
            "carrier/freq | modulator/gain",
            "**/freq | **/label",
            "**/freq | **",
            "a | 0/freq | nope",
            "freq | FREQ",
            "carrier |",
            "*" + "ain | **/" + "*req"
        )

        val options = GraphWalkOptions()
        roots.forEach { (rootName, root) ->
            patterns.forEach { pattern ->
                val slow = GraphWalker.scan(root, options).matching(pattern).paths()
                val fast = root.graphPaths(pattern, options).paths()
                assertEquals(slow, fast, "pattern '$pattern' on $rootName diverged")
            }
        }
    }

    // ── within-segment globs and case ───────────────────────────────────────

    @Test
    fun aGlobInsideASegmentMatchesPartOfAName() {
        val v = voice()

        assertEquals(listOf("carrier/gain", "modulator/gain"), v.graphPaths("**/" + "*ain").paths())
        assertEquals(listOf("carrier", "modulator"), v.graphPaths("*" + "a*r").paths())
        assertEquals(
            listOf("carrier/gain", "carrier/label", "carrier/enabled"),
            v.graphPaths("carrier/" + "*a*").paths()
        )
        assertTrue(v.graphPaths("*" + "Alpha").isEmpty, "nothing here ends in Alpha")
    }

    @Test
    fun aGlobInsideASegmentDoesNotCrossASeparator() {
        val n = Nested(voice(), 2)

        // `*req` is one segment's worth, so the depth-2 freqs are out of reach until `**`
        // says otherwise.
        assertTrue(n.graphPaths("*req").isEmpty, "a within-segment glob is still one segment")
        assertEquals(
            listOf("inner/carrier/freq", "inner/modulator/freq"),
            n.graphPaths("**/" + "*req").paths()
        )

        // And it cannot span a separator even when the joined name would match.
        assertTrue(n.graphPaths("inner*freq").isEmpty, "no crossing a `/`")
    }

    @Test
    fun namesAreMatchedIgnoringCase() {
        val v = voice()

        assertEquals(listOf("carrier/freq"), v.graphPaths("CARRIER/FREQ").paths())
        assertEquals(listOf("carrier/freq", "modulator/freq"), v.graphPaths("**/Freq").paths())
        assertEquals(listOf("carrier/gain", "modulator/gain"), v.graphPaths("**/" + "*AIN").paths())

        // Including the literal single-slot resolver and the set's own lookup.
        assertNotNull(v.graphPath("Carrier/Gain"))
        assertNotNull(v.graphPaths("**")["CARRIER/label"])
    }

    @Test
    fun caseVariantSiblingsAreBothMatched() {
        val twins = mutableMapOf<String, Any?>("freq" to 1f, "Freq" to 2f)

        // Two slots, one pattern. The exactly-spelled one comes first because that is the
        // map's own order, but neither is hidden.
        assertEquals(listOf("freq", "Freq"), twins.graphPaths("freq").paths())
        assertEquals(listOf(1f, 2f), twins.graphPaths("FREQ").values())

        // `graphPath` is single-slot by contract, and prefers the exact spelling.
        assertEquals("freq", twins.graphPath("freq")?.path)
        assertEquals("Freq", twins.graphPath("Freq")?.path)
    }

    // ── alternation ─────────────────────────────────────────────────────────

    @Test
    fun aBarUnionsWholePatterns() {
        val v = voice()

        assertEquals(
            listOf("carrier/freq", "carrier/label", "modulator/freq", "modulator/label"),
            v.graphPaths("**/freq | **/label").paths()
        )

        // Branches of different depths and shapes, and the spaces are optional.
        assertEquals(listOf("carrier", "modulator/gain"), v.graphPaths("carrier|modulator/gain").paths())
        assertEquals(listOf("carrier", "modulator/gain"), v.graphPaths("carrier | modulator/gain").paths())
    }

    @Test
    fun alternationReportsEachSlotOnceInWalkOrder() {
        val v = voice()

        // Overlapping branches: `**` already covers every freq. One walk, so each slot is
        // reported once, and the order is the graph's rather than the pattern's.
        val overlapping = v.graphPaths("**/freq | **").paths()
        assertEquals(v.graphPaths("**").paths(), overlapping)

        // Order follows the walk even when the branches are given back to front.
        assertEquals(
            listOf("carrier/freq", "modulator/gain"),
            v.graphPaths("modulator/gain | carrier/freq").paths()
        )
    }

    @Test
    fun alternationComposesWithGlobsAndCase() {
        val v = voice()

        assertEquals(
            listOf("carrier/gain", "carrier/label", "modulator/gain", "modulator/label"),
            v.graphPaths("**/" + "*ain | **/LABEL").paths()
        )
    }

    @Test
    fun emptyAlternativesAreDropped() {
        val v = voice()

        assertEquals(v.graphPaths("carrier").paths(), v.graphPaths("carrier |").paths())
        assertEquals(v.graphPaths("carrier").paths(), v.graphPaths("| carrier").paths())
        assertTrue(v.graphPaths("|").isEmpty, "nothing but bars matches nothing")
    }

    @Test
    fun alternationIsVisibleOnACompiledPattern() {
        assertEquals(1, GraphQuery.compile("**/freq").alternatives)
        assertEquals(2, GraphQuery.compile("**/freq | gain").alternatives)

        // More than one branch means there is no single path to descend.
        assertTrue(GraphQuery.compile("carrier/freq").isStatic)
        assertFalse(GraphQuery.compile("carrier/freq | modulator/freq").isStatic)
    }

    @Test
    fun matchingAndExcludingSpeakTheSameLanguage() {
        val all = voice().graphPaths("**")
        val some = "**/freq | **/gain"

        assertEquals(listOf("carrier/freq", "carrier/gain", "modulator/freq", "modulator/gain"), all.matching(some).paths())
        assertEquals(all.size, all.matching(some).size + all.excluding(some).size)
    }

    /**
     * Specifically the case that made the naive fast path wrong: a subtree that loops back
     * up through an ancestor. The prefix descent has to hand its ancestor chain to the
     * subtree walk, or the fast path expands a path the full walk prunes.
     */
    @Test
    fun fastPathInheritsAncestorChainThroughBackreferences() {
        val root = Backref("root")
        val child = Backref("child")
        root.up = child
        child.up = root

        val options = GraphWalkOptions()
        listOf("up/" + "**", "up/" + "*", "**", "up/up", "up/up/" + "**").forEach { pattern ->
            val slow = GraphWalker.scan(root, options).matching(pattern).paths()
            val fast = root.graphPaths(pattern, options).paths()
            assertEquals(slow, fast, "pattern '$pattern' diverged across a back-reference")
        }
    }

    @Test
    fun fastPathHonoursIncludeNulls() {
        class Maybe(var value: Float?)

        val excluding = GraphWalkOptions(includeNulls = false)
        assertEquals(0, Maybe(null).graphPaths("value", excluding).size)
        assertEquals(1, Maybe(null).graphPaths("value").size)
    }

    @Test
    fun graphPathResolvesASingleLiteralAddress() {
        val v = voice()
        val ref = v.graphPath("carrier/freq")

        assertNotNull(ref)
        assertEquals(220f, ref.get())
        assertSame(v.carrier, ref.owner)
        assertNull(v.graphPath("carrier/nope"))
        assertNull(v.graphPath("carrier/" + "*"), "a glob is not a literal address")
    }

    // ── the fast path is actually fast ──────────────────────────────────────

    /**
     * A deterministic cost assertion rather than a wall-clock benchmark: count how many
     * edge getters each strategy invokes. A literal path reads exactly one getter per
     * segment; a full `**` walk reads every edge in the graph.
     */
    @Test
    fun literalPathsReadOnlyTheSegmentsTheyName() {
        val reads = intArrayOf(0)

        /** A tree whose every edge read is counted. */
        class Counted(val label: String) : LiveGraphInspectable {
            val kids = linkedMapOf<String, Counted>()
            var leaf: Float = 1f
            override fun liveGraphChildren(): Iterable<LiveGraphEdge> =
                kids.map { (name, child) -> LiveGraphEdge(name, { reads[0]++; child }) } +
                        LiveGraphEdge("leaf", { reads[0]++; leaf }, { v -> leaf = (v as Number).toFloat() })
        }

        fun build(depth: Int, breadth: Int, label: String): Counted {
            val node = Counted(label)
            if (depth > 0) {
                repeat(breadth) { i -> node.kids["k$i"] = build(depth - 1, breadth, "$label.$i") }
            }
            return node
        }

        val root = build(depth = 3, breadth = 5, label = "root")

        reads[0] = 0
        val deep = root.graphPaths("k1/k2/k3/leaf")
        val staticReads = reads[0]
        assertEquals(1, deep.size, "the literal path resolves")
        assertEquals(4, staticReads, "one getter per named segment, nothing else")

        reads[0] = 0
        val everything = root.graphPaths("**")
        val fullReads = reads[0]
        assertTrue(everything.size > 100, "the graph really is large: ${everything.size}")
        assertTrue(
            fullReads > staticReads * 20,
            "a full walk must cost far more than a literal path: $fullReads vs $staticReads"
        )
    }

    // ── traversal policy knobs ──────────────────────────────────────────────

    @Test
    fun maxRefsBoundsTheResult() {
        val v = Nested(voice(), 1)
        val capped = v.graphPaths("**", GraphWalkOptions(maxRefs = 3))

        assertTrue(capped.size <= 3, "got ${capped.size}")
    }

    /**
     * The policy hook takes the default decision as an argument, so pruning one type keeps
     * every built-in guard. Replacing it outright would descend into `String` and start
     * reporting `name/length`.
     */
    @Test
    fun descendIntoPrunesWithoutLosingTheDefaultGuards() {
        val v = voice()
        val shallow = v.graphPaths(
            "**",
            GraphWalkOptions(descendInto = { value, byDefault -> byDefault && value !is Osc })
        )

        assertEquals(listOf("carrier", "modulator", "name"), shallow.paths())
        assertTrue(v.graphPaths("**").size > shallow.size, "the default would open Osc")
    }

    /** The same hook can deliberately opt into something the default refuses to open. */
    @Test
    fun descendIntoCanExpandBeyondTheDefault() {
        class Holder(var label: String)

        assertEquals(listOf("label"), Holder("hi").graphPaths("**").paths())

        val expanded = Holder("hi").graphPaths(
            "**",
            GraphWalkOptions(
                maxDepth = 1,
                descendInto = { value, byDefault -> byDefault || value is String }
            )
        )
        assertTrue(expanded.paths().contains("label/length"), "opted into String: ${expanded.paths()}")
    }

    @Test
    fun nullAndScalarRootsDegradeGracefully() {
        assertTrue((null as Any?).graphPaths("**").isEmpty)
        assertTrue(42f.graphPaths("**").isEmpty, "a scalar root has no children to report")
        assertNull((null as Any?).graphPath("a/b"))
    }

    @Test
    fun refExposesNameAndDepth() {
        val ref = Nested(voice(), 1).graphPath("inner/carrier/freq")!!

        assertEquals("freq", ref.name)
        assertEquals(3, ref.depth)
        assertEquals("inner/carrier/freq", ref.path)
    }

    @Test
    fun typedRefReadReturnsNullOnTypeMismatch() {
        val ref = voice().graphPath("carrier/freq")!!

        assertEquals(220f, ref.getAs<Float>())
        assertNull(ref.getAs<String>(), "wrong type reads as null rather than throwing")
    }

    @Test
    fun trySetReportsRatherThanThrowsOnReadOnlySlots() {
        class Fixed(val a: Float)

        val ref = Fixed(1f).graphPath("a")!!
        assertFalse(ref.writable)
        assertFalse(ref.trySet(2f))
    }
}
