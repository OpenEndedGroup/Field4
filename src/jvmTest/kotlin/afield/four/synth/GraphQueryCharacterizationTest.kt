package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterization tests for the GraphQuery reflective path layer.
 *
 * These pin the *current* observable semantics of `graphPaths` / `params` / `matches`
 * before the layer is generalized and moved out of `afield.four.synth`. Several of the
 * behaviours asserted here are warts rather than intentions — where that is the case the
 * test says so explicitly, so that a later deliberate change shows up as an edited
 * assertion rather than a silent behavioural drift.
 *
 * Everything in this file stays clear of `Runloop`: scalar path writes apply straight
 * through the setter, so only `modulate`/`supply` and native-[Param] writes touch the
 * binding runtime (those live in [GraphQuerySynthHeadTest]).
 */
class GraphQueryCharacterizationTest {

    // ── fixtures ────────────────────────────────────────────────────────────
    // Deliberately declared in the test package: the walker refuses to descend into
    // java.*/javax.*/jdk.*/sun.* types, so fixtures must be first-party to be traversed.

    class Osc(var freq: Float, var gain: Float)

    class Voice(val carrier: Osc, val modulator: Osc)

    class Aliased(val a: Osc, val b: Osc)

    class Labelled(var label: String, var osc: Osc)

    class Knobs(var steps: Int, var ratio: Double, var count: Long)

    class Deep(val inner: Voice)

    class Ring(var name: String) {
        var next: Ring? = null
    }

    class Immutable(val fixed: Float, var movable: Float)

    class Custom(var real: Float) : LiveGraphInspectable {
        var hidden: Float = 9f
        override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
            LiveGraphEdge("synthetic", { hidden }, { v -> hidden = (v as Number).toFloat() })
        )
    }

    private fun voice() = Voice(Osc(220f, 0.8f), Osc(440f, 0.2f))

    // ── walk shape and ordering ─────────────────────────────────────────────

    /**
     * The walk is depth-first and interleaved: a ref is recorded for every edge crossed
     * (including non-leaf edges like `carrier` itself), and property order follows the
     * primary constructor, not the alphabet.
     */
    @Test
    fun walkIsDepthFirstInConstructorOrderAndRecordsInteriorEdges() {
        val paths = voice().graphPaths("**").paths()
        assertEquals(
            listOf("carrier", "carrier/freq", "carrier/gain", "modulator", "modulator/freq", "modulator/gain"),
            paths
        )
    }

    /** Numbers/strings/enums are leaves — the walker records them but does not descend. */
    @Test
    fun leafScalarsAreNotDescendedInto() {
        val paths = Labelled("hello", Osc(1f, 2f)).graphPaths("**").paths()
        assertEquals(listOf("label", "osc", "osc/freq", "osc/gain"), paths)
        assertTrue(paths.none { it.startsWith("label/") }, "must not reflect into String internals")
    }

    /** Reference cycles terminate via an identity visited-set rather than depth alone. */
    @Test
    fun cyclesTerminate() {
        val a = Ring("a")
        val b = Ring("b")
        a.next = b
        b.next = a

        val paths = a.graphPaths("**").paths()
        assertEquals(listOf("name", "next", "next/name", "next/next"), paths)
    }

    /** maxDepth clips expansion; the clipped edge itself is still recorded. */
    @Test
    fun maxDepthClipsExpansion() {
        val paths = voice().graphPaths("**", GraphWalkOptions(maxDepth = 0)).paths()
        assertEquals(listOf("carrier", "modulator"), paths)
    }

    /**
     * WART: an object reached by two different edges is only *expanded* once, so the
     * second alias has no children. `b/freq` is simply missing from the result.
     */
    @Test
    fun aliasedNodesAreExpandedOnlyOnce() {
        val shared = Osc(100f, 0.5f)
        val paths = Aliased(shared, shared).graphPaths("**").paths()

        assertEquals(listOf("a", "a/freq", "a/gain", "b"), paths)
        assertTrue(paths.none { it == "b/freq" }, "second alias is not expanded (known limitation)")
    }

    // ── glob semantics ──────────────────────────────────────────────────────

    /** `**` matches any number of segments, including zero; `*` matches exactly one. */
    @Test
    fun globSemantics() {
        val v = voice()

        assertEquals(6, v.graphPaths("**").size, "** matches everything")
        assertEquals(listOf("carrier", "modulator"), v.graphPaths("*").paths())
        assertEquals(listOf("carrier/freq", "carrier/gain"), v.graphPaths("carrier/*").paths())
        assertEquals(listOf("carrier/freq", "modulator/freq"), v.graphPaths("**/freq").paths())
        assertEquals(listOf("carrier/freq"), v.graphPaths("carrier/freq").paths())
        assertEquals(0, v.graphPaths("nope").size)
    }

    /** A leading `**` can absorb zero segments, so `**\/x` also matches a top-level `x`. */
    @Test
    fun doubleStarAbsorbsZeroSegments() {
        val paths = Osc(1f, 2f).graphPaths("**/freq").paths()
        assertEquals(listOf("freq"), paths)
    }

    /** Deeper nesting: `**` spans more than one intermediate segment. */
    @Test
    fun doubleStarSpansMultipleSegments() {
        val paths = Deep(voice()).graphPaths("**/freq").paths()
        assertEquals(listOf("inner/carrier/freq", "inner/modulator/freq"), paths)
    }

    // ── writability ─────────────────────────────────────────────────────────

    /** `var` yields a writable ref, `val` does not. */
    @Test
    fun onlyMutablePropertiesAreWritable() {
        val set = Immutable(1f, 2f).graphPaths("**")
        val byPath = set.associateBy { it.path }

        assertFalse(byPath.getValue("fixed").writable, "val is read-only")
        assertTrue(byPath.getValue("movable").writable, "var is writable")
        assertEquals(listOf("movable"), set.writable().paths())
    }

    /** A writable ref really writes through to the live object. */
    @Test
    fun writableRefsWriteThrough() {
        val osc = Osc(220f, 0.8f)
        val ref = osc.graphPaths("freq").firstOrNull()!!

        ref.set(330f)
        assertEquals(330f, osc.freq, 0f)
        assertEquals(330f, ref.get())
    }

    /** Mutable collections expose writable element edges keyed by index / stringified key. */
    @Test
    fun mutableListAndMapElementsAreWritable() {
        val list = mutableListOf<Any?>(1f, 2f)
        val listSet = list.graphPaths("**")
        assertEquals(listOf("0", "1"), listSet.paths())
        assertTrue(listSet.all { it.writable }, "MutableList elements are writable")
        listSet.firstOrNull()!!.set(7f)
        assertEquals(7f, list[0])

        val map = mutableMapOf<String, Any?>("a" to 1f, "b" to 2f)
        val mapSet = map.graphPaths("**")
        assertEquals(listOf("a", "b"), mapSet.paths())
        assertTrue(mapSet.all { it.writable }, "MutableMap entries are writable")
        mapSet.firstOrNull()!!.set(7f)
        assertEquals(7f, map["a"])
    }

    /**
     * WART (gap 4): `Array` elements are read-only even though mutable List/Map elements
     * are writable. Arrays are perfectly mutable in Kotlin — this is an inconsistency,
     * not a constraint.
     */
    @Test
    fun arrayElementsAreCurrentlyReadOnly() {
        val arr = arrayOf<Any?>(1f, 2f)
        val set = arr.graphPaths("**")

        assertEquals(listOf("0", "1"), set.paths())
        assertTrue(set.none { it.writable }, "arrays currently expose no setters")
        assertEquals(0, set.writable().size)
    }

    /** Primitive arrays are leaves: recorded, never indexed into. */
    @Test
    fun primitiveArraysAreLeaves() {
        class HasBuffer(var buf: FloatArray)

        val paths = HasBuffer(floatArrayOf(1f, 2f, 3f)).graphPaths("**").paths()
        assertEquals(listOf("buf"), paths)
    }

    // ── options that do (and do not) work ───────────────────────────────────

    /**
     * WART: `includeNulls` is dead. The live walk records every edge unconditionally, so
     * null-valued edges are *always* present regardless of the flag. The flag was only
     * honoured by an unreachable code path.
     */
    @Test
    fun includeNullsIsIgnoredNullsAlwaysAppear() {
        class Maybe(var value: Float?, var other: Float)

        val withoutFlag = Maybe(null, 1f).graphPaths("**", GraphWalkOptions(includeNulls = false)).paths()
        val withFlag = Maybe(null, 1f).graphPaths("**", GraphWalkOptions(includeNulls = true)).paths()

        assertEquals(listOf("value", "other"), withoutFlag, "null edge present even when flag is false")
        assertEquals(withoutFlag, withFlag, "the flag changes nothing")
        assertNull(Maybe(null, 1f).graphPaths("value").firstOrNull()!!.get())
    }

    /** Private properties are hidden by default and revealed by `includePrivate`. */
    @Test
    fun includePrivateRevealsPrivateProperties() {
        class Secretive(var open: Float) {
            @Suppress("unused")
            private var closed: Float = 3f
        }

        val public = Secretive(1f).graphPaths("**").paths()
        val private = Secretive(1f).graphPaths("**", GraphWalkOptions(includePrivate = true)).paths()

        assertEquals(listOf("open"), public)
        assertTrue(private.contains("closed"), "private property visible with includePrivate: $private")
    }

    // ── LiveGraphInspectable ────────────────────────────────────────────────

    /** Custom edges are merged ahead of reflected ones and are independently writable. */
    @Test
    fun customEdgesTakePrecedenceAndMergeWithReflection() {
        val subject = Custom(1f)
        val paths = subject.graphPaths("**").paths()

        assertEquals("synthetic", paths.first(), "custom edges come first")
        assertTrue(paths.contains("real"), "reflected properties still present: $paths")

        subject.graphPaths("synthetic").firstOrNull()!!.set(42f)
        assertEquals(42f, subject.hidden, 0f, "custom setter writes through")
    }

    // ── PathSet combinators ─────────────────────────────────────────────────

    /** `ofType` filters on the *value's* runtime type. */
    @Test
    fun ofTypeFiltersByValueType() {
        val set = Labelled("hi", Osc(1f, 2f)).graphPaths("**")

        assertEquals(listOf("label"), set.ofType<String>().paths())
        assertEquals(listOf("osc/freq", "osc/gain"), set.ofType<Float>().paths())
        assertEquals(listOf("osc"), set.ofType<Osc>().paths())
    }

    /** `matching` narrows an already-computed set with the same glob rules. */
    @Test
    fun matchingNarrowsAnExistingSet() {
        val all = voice().graphPaths("**")
        assertEquals(listOf("carrier/freq", "modulator/freq"), all.matching("**/freq").paths())
    }

    /** `dedup` collapses refs that address the same (owner, slot) pair; `deduplicated` aliases it. */
    @Test
    fun dedupCollapsesRefsAddressingTheSameSlot() {
        val shared = Osc(100f, 0.5f)
        val set = Aliased(shared, shared).graphPaths("**")

        // "a" and "b" are distinct slots on the root, so nothing collapses here.
        assertEquals(set.size, set.dedup().size)
        assertEquals(set.dedup().paths(), set.deduplicated().paths())
    }

    /** `values()` reads live, so it reflects mutation after the query was built. */
    @Test
    fun valuesAreReadLive() {
        val osc = Osc(220f, 0.8f)
        val set = osc.graphPaths("freq")

        assertEquals(listOf<Any?>(220f), set.values())
        osc.freq = 330f
        assertEquals(listOf<Any?>(330f), set.values(), "PathSet holds getters, not snapshots")
    }

    /** The root is reported as `<root>` when a ref has no segments; paths are slash-joined. */
    @Test
    fun pathFormatting() {
        val ref = Deep(voice()).graphPaths("inner/carrier/freq").firstOrNull()!!
        assertEquals("inner/carrier/freq", ref.path)
        assertEquals("inner/carrier/freq", ref.rawPath)
        assertEquals("inner/carrier/freq", ref.toString())
    }

    /** `owner` is the object the edge was read from, by identity. */
    @Test
    fun ownerIsTheEnclosingObject() {
        val v = voice()
        val ref = v.graphPaths("carrier/freq").firstOrNull()!!
        assertSame(v.carrier, ref.owner)
    }

    /** A null root yields an empty set rather than throwing. */
    @Test
    fun nullRootYieldsEmptySet() {
        val set = (null as Any?).graphPaths("**")
        assertTrue(set.isEmpty)
        assertEquals(0, set.size)
    }

    // ── ParamSet over plain scalars (no Runloop involvement) ────────────────

    /** `params()` promotes writable numeric leaves into a settable ParamSet. */
    @Test
    fun paramsPromotesWritableNumericLeaves() {
        val v = voice()
        val freqs = v.params("**/freq")

        assertEquals(listOf("carrier/freq", "modulator/freq"), freqs.paths())
        assertEquals(listOf(220f, 440f), freqs.bases())
        assertTrue(freqs.params().isEmpty(), "plain scalars are not native Params")
    }

    /** Setting a scalar ParamSet writes through to the underlying fields immediately. */
    @Test
    fun scalarParamSetWritesThrough() {
        val v = voice()
        v.params("**/freq").set(0.15f)

        assertEquals(0.15f, v.carrier.freq, 1e-6f)
        assertEquals(0.15f, v.modulator.freq, 1e-6f)
    }

    /** Arithmetic combinators operate elementwise on the live fields. */
    @Test
    fun scalarParamSetArithmetic() {
        val v = voice()
        v.params("**/freq").scale(0.5f)
        assertEquals(110f, v.carrier.freq, 1e-6f)
        assertEquals(220f, v.modulator.freq, 1e-6f)

        v.params("**/freq").offset(10f)
        assertEquals(120f, v.carrier.freq, 1e-6f)

        v.params("**/freq").clamp(0f, 100f)
        assertEquals(100f, v.carrier.freq, 1e-6f)
        assertEquals(100f, v.modulator.freq, 1e-6f)
    }

    /** `set(Iterable)` zips, so a short list leaves later entries untouched. */
    @Test
    fun setWithIterableZipsAndStopsShort() {
        val v = voice()
        v.params("**/freq").set(listOf(1f))

        assertEquals(1f, v.carrier.freq, 1e-6f)
        assertEquals(440f, v.modulator.freq, 1e-6f, "unzipped entry untouched")
    }

    /** Numeric writes are coerced back to each field's declared type. */
    @Test
    fun numericTypesAreCoercedOnWrite() {
        val knobs = Knobs(steps = 1, ratio = 0.0, count = 0L)

        knobs.params("steps").set(3.7f)
        knobs.params("ratio").set(0.25f)
        knobs.params("count").set(9.2f)

        assertEquals(4, knobs.steps, "Int rounds")
        assertEquals(0.25, knobs.ratio, 1e-6, "Double widens")
        assertEquals(9L, knobs.count, "Long rounds")
    }

    /** Read-only numeric leaves are excluded from a ParamSet entirely. */
    @Test
    fun readOnlyNumericLeavesAreExcluded() {
        val set = Immutable(1f, 2f).params("**")
        assertEquals(listOf("movable"), set.paths())
    }

    /** ParamSet entries are sorted by path, independent of walk order. */
    @Test
    fun paramSetIsSortedByPath() {
        class Jumbled(var zebra: Float, var apple: Float)

        assertEquals(listOf("apple", "zebra"), Jumbled(1f, 2f).params("**").paths())
    }

    /** The `matches` infix is sugar for `params(pattern)`. */
    @Test
    fun matchesInfixEqualsParams() {
        val v = voice()
        assertEquals(v.params("**/freq").paths(), (v matches "**/freq").paths())
    }

    // ── ParamSetScope set-relative shaping ──────────────────────────────────

    /** `ramp` distributes across the range by index. */
    @Test
    fun scopeRampDistributesByIndex() {
        val v = voice()
        v.params("**/freq").rewrite { ramp(0f..10f) }

        assertEquals(0f, v.carrier.freq, 1e-6f)
        assertEquals(10f, v.modulator.freq, 1e-6f)
    }

    /** A single-entry `ramp` lands on the range midpoint rather than the start. */
    @Test
    fun scopeRampOfOneEntryIsMidpoint() {
        val osc = Osc(1f, 2f)
        osc.params("freq").rewrite { ramp(0f..10f) }
        assertEquals(5f, osc.freq, 1e-6f)
    }

    /** `normalize` rescales the observed min..max onto the requested range. */
    @Test
    fun scopeNormalizeRescalesObservedRange() {
        val v = Voice(Osc(100f, 0f), Osc(200f, 0f))
        v.params("**/freq").rewrite { normalize(0f..1f) }

        assertEquals(0f, v.carrier.freq, 1e-6f)
        assertEquals(1f, v.modulator.freq, 1e-6f)
    }

    /** A degenerate (flat) input collapses to the midpoint instead of dividing by zero. */
    @Test
    fun scopeNormalizeOfFlatInputCollapsesToMidpoint() {
        val v = Voice(Osc(100f, 0f), Osc(100f, 0f))
        v.params("**/freq").rewrite { normalize(0f..1f) }

        assertEquals(0.5f, v.carrier.freq, 1e-6f)
        assertEquals(0.5f, v.modulator.freq, 1e-6f)
    }

    /** Scope statistics read the live entry values. */
    @Test
    fun scopeStatistics() {
        val v = Voice(Osc(100f, 0f), Osc(300f, 0f))
        v.params("**/freq").rewrite {
            assertEquals(2, size)
            assertEquals(200f, mean, 1e-6f)
            assertEquals(100f, min, 1e-6f)
            assertEquals(300f, max, 1e-6f)
        }
    }

    /** Deterministic randomness: a seeded Random keeps every value inside the range. */
    @Test
    fun randomStaysInRange() {
        val v = voice()
        v.params("**/freq").random(200f..400f, kotlin.random.Random(42))

        assertTrue(v.carrier.freq in 200f..400f, "got ${v.carrier.freq}")
        assertTrue(v.modulator.freq in 200f..400f, "got ${v.modulator.freq}")
    }

    /** `choose` only ever picks values from the supplied options. */
    @Test
    fun chooseSelectsFromOptions() {
        val v = voice()
        val options = listOf(1f, 2f, 3f)
        v.params("**/freq").choose(options, kotlin.random.Random(7))

        assertTrue(v.carrier.freq in options, "got ${v.carrier.freq}")
        assertTrue(v.modulator.freq in options, "got ${v.modulator.freq}")
    }

    /**
     * WART: the per-slot scalar state is cached globally by (owner identity, slot), and it
     * snapshots `base` when first created. A later *external* write to the same field is
     * therefore invisible to a fresh query — `bases()` returns the stale cached value.
     */
    @Test
    fun scalarStateIsCachedGloballyAndGoesStale() {
        val osc = Osc(220f, 0.8f)
        assertEquals(listOf(220f), osc.params("freq").bases())

        osc.freq = 999f
        assertEquals(
            listOf(220f),
            osc.params("freq").bases(),
            "cached ScalarPathState does not observe external writes (known wart)"
        )
    }

    // ── ToHTML rendering (used by the docs captures) ─────────────────────────

    /** Both result types render a real HTML table, which is what the docs pages capture. */
    @Test
    fun setsRenderAsHtmlTables() {
        val v = voice()

        val pathHtml = v.graphPaths("**").toHTML()
        assertTrue(pathHtml.contains("PathSet"), pathHtml)
        assertTrue(pathHtml.contains("carrier/freq"), pathHtml)
        assertTrue(pathHtml.contains("<table"), pathHtml)

        val paramHtml = v.params("**/freq").toHTML()
        assertTrue(paramHtml.contains("ParamSet"), paramHtml)
        assertTrue(paramHtml.contains("carrier/freq"), paramHtml)
        assertTrue(paramHtml.contains("scalar"), "scalar-backed entries are labelled: $paramHtml")
    }

    /** HTML is escaped, so a hostile map key cannot inject markup. */
    @Test
    fun htmlIsEscaped() {
        val map = mutableMapOf<String, Any?>("<script>" to 1f)
        val html = map.graphPaths("**").toHTML()

        assertTrue(html.contains("&lt;script&gt;"), html)
        assertFalse(html.contains("<script>"), "raw markup must not survive: $html")
    }
}
