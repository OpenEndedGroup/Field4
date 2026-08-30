package afield.graphquery

/*
FIELD_TEST
id: graphquery.core.smoke
runner: jvm-main
entrypoint: afield.graphquery.GraphQuerySmokeTest
tags: [smoke, graphquery, reflection]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "GraphQuery smoke passed"
*/
/**
 * Smoke for the reflective path layer ([afield.graphquery]) — the XPath-style query engine
 * over arbitrary live object graphs.
 *
 * Deliberately exercises only the generic core, which is what lets this run as a plain
 * `jvm-main` entrypoint at all: since the layer was split out of `afield.four.synth` the
 * core touches no `Runloop`, spawns no threads and opens no devices, so the JVM exits
 * naturally. The synth-coupled parameter head (modulation, suppliers, `GraphBindingRuntime`)
 * does register a runloop hook and is covered by the `jvmTest` suites instead.
 *
 * The load-bearing case here is [testFastPathAgreesWithFullWalk]: the literal-prefix
 * optimization must return exactly what a full traversal plus glob filter returns, or the
 * two resolution strategies would quietly disagree.
 */
object GraphQuerySmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "GraphQuery smoke tests failed" }
    }

    fun runTests(): Boolean = try {
        println("Running GraphQuery smoke...")
        testWalkShapeAndOrdering()
        testGlobSemantics()
        testCycleAndAliasHandling()
        testWritability()
        testPredicates()
        testSlashOperator()
        testTypedLenses()
        testCompiledPatterns()
        testFastPathAgreesWithFullWalk()
        testFastPathCost()
        testCustomEdges()
        testPolicyKnobs()
        testHtmlRendering()
        println("GraphQuery smoke passed")
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        false
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private enum class Mode { OFF, ON }

    private class Osc(var freq: Float, var gain: Float, var label: String, var enabled: Boolean, var mode: Mode)

    private class Voice(val carrier: Osc, val modulator: Osc, var name: String)

    private class Nested(val inner: Voice, var depth: Int)

    private class Backref(var label: String) {
        var up: Any? = null
    }

    private class Custom(var real: Float) : LiveGraphInspectable {
        var hidden: Float = 9f
        override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
            LiveGraphEdge("synthetic", { hidden }, { v -> hidden = (v as Number).toFloat() })
        )
    }

    private fun osc(freq: Float = 220f, gain: Float = 0.5f) = Osc(freq, gain, "osc", true, Mode.ON)

    private fun voice() = Voice(osc(220f, 0.8f), osc(440f, 0.2f), "voice")

    private fun require(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    private fun <T> requireEquals(expected: T, actual: T, what: String) {
        if (expected != actual) throw AssertionError("$what: expected <$expected> but was <$actual>")
    }

    // ── cases ───────────────────────────────────────────────────────────────

    /** Depth-first, interior edges recorded, properties in primary-constructor order. */
    private fun testWalkShapeAndOrdering() {
        requireEquals(
            listOf(
                "carrier", "carrier/freq", "carrier/gain", "carrier/label", "carrier/enabled", "carrier/mode",
                "modulator", "modulator/freq", "modulator/gain", "modulator/label", "modulator/enabled",
                "modulator/mode", "name"
            ),
            voice().graphPaths("**").paths(),
            "walk order"
        )
        println("  ok: walk shape and constructor ordering")
    }

    private fun testGlobSemantics() {
        val v = voice()
        requireEquals(listOf("carrier", "modulator", "name"), v.graphPaths("*").paths(), "single star")
        requireEquals(
            listOf("carrier/freq", "modulator/freq"),
            v.graphPaths("**/freq").paths(),
            "double star"
        )
        requireEquals(listOf("carrier/freq"), v.graphPaths("carrier/freq").paths(), "literal")
        requireEquals(0, v.graphPaths("nope").size, "no match")
        // ** absorbs zero segments
        requireEquals(listOf("freq"), osc().graphPaths("**/freq").paths(), "zero-segment double star")

        // A `*` inside a segment globs within that one name, and never across a `/`.
        requireEquals(
            listOf("carrier/gain", "modulator/gain"),
            v.graphPaths("**/" + "*ain").paths(),
            "glob inside a segment"
        )
        requireEquals(listOf("carrier", "modulator"), v.graphPaths("*a*r").paths(), "glob at one level")
        requireEquals(0, v.graphPaths("*req").size, "a within-segment glob is still one segment")
        requireEquals(0, v.graphPaths("carrier*freq").size, "a within-segment glob cannot cross a /")

        // Names are matched ignoring case, literal segments included.
        requireEquals(listOf("carrier/freq"), v.graphPaths("CARRIER/Freq").paths(), "case-insensitive literal")
        requireEquals(
            listOf("carrier/gain", "modulator/gain"),
            v.graphPaths("**/" + "*AIN").paths(),
            "case-insensitive glob"
        )

        // Two spellings of one name are two slots, and one pattern finds both. The literal
        // fast path has to notice that and defer to the walk, which is what this pins.
        val twins = mutableMapOf<String, Any?>("freq" to 1f, "Freq" to 2f)
        requireEquals(listOf("freq", "Freq"), twins.graphPaths("freq").paths(), "case-variant siblings")

        // `|` unions whole patterns: one walk, each slot once, in walk order.
        requireEquals(
            listOf("carrier/freq", "carrier/label", "modulator/freq", "modulator/label"),
            v.graphPaths("**/freq | **/label").paths(),
            "alternation"
        )
        requireEquals(
            v.graphPaths("**").paths(),
            v.graphPaths("**/freq | **").paths(),
            "overlapping branches report each slot once"
        )
        requireEquals(
            listOf("carrier", "modulator/gain"),
            v.graphPaths("modulator/gain|carrier").paths(),
            "branch order does not change walk order"
        )
        requireEquals(0, v.graphPaths("|").size, "nothing but bars matches nothing")

        println("  ok: glob semantics")
    }

    /** Path-scoped cycle detection: cycles stop, aliases still expand under each address. */
    private fun testCycleAndAliasHandling() {
        val a = Backref("a")
        val b = Backref("b")
        a.up = b
        b.up = a
        requireEquals(
            listOf("label", "up", "up/label", "up/up"),
            a.graphPaths("**").paths(),
            "cycle termination"
        )

        val shared = osc(100f)
        class Aliased(val x: Osc, val y: Osc)
        val paths = Aliased(shared, shared).graphPaths("**").paths()
        require(paths.contains("x/freq") && paths.contains("y/freq")) {
            "both aliases must expand, got $paths"
        }
        val viaSecond = Aliased(shared, shared).graphPath("y/freq")
        require(viaSecond != null && viaSecond.get() == 100f) { "second alias must be addressable" }
        println("  ok: cycles terminate and aliases expand")
    }

    private fun testWritability() {
        val v = voice()
        v.graphPath("carrier/freq")!!.set(330f)
        requireEquals(330f, v.carrier.freq, "write through a ref")

        class Fixed(val a: Float, var b: Float)
        val set = Fixed(1f, 2f).graphPaths("**")
        requireEquals(listOf("b"), set.writable().paths(), "val is read-only")

        // Arrays are writable, consistently with mutable collections.
        val arr = arrayOf<Any?>(1f, 2f)
        arr.graphPaths("0").first().set(7f)
        requireEquals(7f, arr[0], "array element write")

        val list = mutableListOf<Any?>(1f)
        list.graphPaths("0").first().set(5f)
        requireEquals(5f, list[0], "list element write")

        val map = mutableMapOf<String, Any?>("k" to 1f)
        map.graphPaths("k").first().set(3f)
        requireEquals(3f, map["k"], "map entry write")
        println("  ok: writability across properties, arrays, lists and maps")
    }

    private fun testPredicates() {
        val v = voice()
        requireEquals(
            listOf("modulator/freq"),
            v.graphPaths("**").whereValueIs<Float> { it > 300f }.paths(),
            "typed value predicate"
        )
        requireEquals(
            listOf("carrier/gain"),
            v.graphPaths("**/gain").whereValue { (it as? Float ?: 0f) > 0.5f }.paths(),
            "value predicate"
        )
        require(v.graphPaths("**").ownedBy(v.carrier).all { it.owner === v.carrier }) { "ownedBy" }
        requireEquals(
            listOf("carrier", "modulator", "name"),
            v.graphPaths("**").atDepth(1..1).paths(),
            "atDepth"
        )
        val all = v.graphPaths("**")
        requireEquals(
            all.size,
            all.excluding("**/gain").size + all.matching("**/gain").size,
            "excluding is the complement of matching"
        )
        println("  ok: predicates")
    }

    private fun testSlashOperator() {
        val v = voice()

        // The sugar is the same query.
        requireEquals(
            v.graphPaths("**/freq").paths(),
            (v / "**/freq").paths(),
            "slash is graphPaths"
        )

        // A chain splits the pattern without losing the address it was reached by.
        requireEquals(listOf("carrier/freq"), (v / "carrier" / "freq").paths(), "chained slash")
        (v / "carrier" / "freq").lenses<Float>().set(330f)
        requireEquals(330f, v.carrier.freq, "write through a chained slash")

        // A typed leaf has nothing below it, so this reports nothing rather than the
        // LensSet's own fields.
        require(((v / "**/freq").lenses<Float>() / "**").isEmpty) { "slash off a typed leaf" }

        println("  ok: slash operator")
    }

    private fun testTypedLenses() {
        val v = voice()
        requireEquals(3, v.graphPaths("**").lenses<String>().set("x"), "string lenses written")
        requireEquals("x", v.carrier.label, "lens write through")

        v.graphPaths("**").lenses<Boolean>().update { !it }
        requireEquals(false, v.carrier.enabled, "boolean lens update")

        v.graphPaths("**").lenses<Mode>().set(Mode.OFF)
        requireEquals(Mode.OFF, v.carrier.mode, "enum lens set")

        // A lens only ever sees values of its own type, so this cannot mis-cast.
        requireEquals(2, v.graphPaths("**/freq").lenses<Float>().size, "float lenses")
        requireEquals(0, v.graphPaths("**/freq").lenses<String>().size, "no string freqs")
        println("  ok: typed lenses over String, Boolean, enum and Float")
    }

    private fun testCompiledPatterns() {
        val a = GraphQuery.compile("**/freq")
        require(a === GraphQuery.compile("**/freq")) { "compiled patterns must be cached" }
        require(GraphQuery.compile("carrier/freq").isStatic) { "literal pattern is static" }
        require(!GraphQuery.compile("**").isStatic) { "glob pattern is not static" }

        val v = voice()
        requireEquals(listOf("carrier/freq", "modulator/freq"), a.resolve(v).paths(), "reuse a plan")
        v.carrier.freq = 1f
        requireEquals(listOf<Any?>(1f, 440f), a.resolve(v).values(), "plans read live")
        println("  ok: compiled patterns")
    }

    /**
     * The optimized resolver must be indistinguishable from a full traversal plus filter.
     * Includes a back-reference graph, which is where a naive prefix descent diverges.
     */
    private fun testFastPathAgreesWithFullWalk() {
        val backref = Backref("root").also { root ->
            root.up = Backref("child").also { it.up = root }
        }
        val roots: List<Pair<String, Any>> = listOf(
            "voice" to voice(),
            "nested" to Nested(voice(), 2),
            "map" to mutableMapOf<String, Any?>("a" to osc(), "b" to osc(), "n" to null),
            "list" to mutableListOf<Any?>(osc(), osc()),
            "array" to arrayOf<Any?>(osc(), osc()),
            "backref" to backref
        )
        val patterns = listOf(
            "**", "*", "**/freq", "*/freq",
            "carrier", "carrier/freq", "carrier/" + "*", "carrier/" + "**",
            "inner/carrier/freq", "inner/" + "*" + "/freq", "inner/" + "**",
            "a/freq", "a/" + "*", "0/freq", "n",
            "up/" + "**", "up/" + "*", "up/up",
            "nope", "carrier/nope", "**/nope"
        )
        val options = GraphWalkOptions()
        var checked = 0
        roots.forEach { (rootName, root) ->
            patterns.forEach { pattern ->
                val slow = GraphWalker.scan(root, options).matching(pattern).paths()
                val fast = root.graphPaths(pattern, options).paths()
                requireEquals(slow, fast, "pattern '$pattern' on $rootName")
                checked++
            }
        }
        println("  ok: fast path matches full walk across $checked root/pattern combinations")
    }

    /** A literal path reads one getter per named segment; a full walk reads every edge. */
    private fun testFastPathCost() {
        val reads = intArrayOf(0)

        class Counted(val label: String) : LiveGraphInspectable {
            val kids = linkedMapOf<String, Counted>()
            var leaf: Float = 1f
            override fun liveGraphChildren(): Iterable<LiveGraphEdge> =
                kids.map { (name, child) -> LiveGraphEdge(name, { reads[0]++; child }) } +
                        LiveGraphEdge("leaf", { reads[0]++; leaf }, { v -> leaf = (v as Number).toFloat() })
        }

        fun build(depth: Int, breadth: Int, label: String): Counted {
            val node = Counted(label)
            if (depth > 0) repeat(breadth) { i -> node.kids["k$i"] = build(depth - 1, breadth, "$label.$i") }
            return node
        }

        val root = build(depth = 3, breadth = 5, label = "root")

        reads[0] = 0
        requireEquals(1, root.graphPaths("k1/k2/k3/leaf").size, "literal path resolves")
        val staticReads = reads[0]
        requireEquals(4, staticReads, "one getter per named segment")

        reads[0] = 0
        val everything = root.graphPaths("**")
        val fullReads = reads[0]
        require(everything.size > 100) { "graph should be large, got ${everything.size}" }
        require(fullReads > staticReads * 20) {
            "full walk ($fullReads) should cost far more than a literal path ($staticReads)"
        }
        println("  ok: literal path cost $staticReads edge reads vs $fullReads for a full walk")
    }

    private fun testCustomEdges() {
        val subject = Custom(1f)
        val paths = subject.graphPaths("**").paths()
        requireEquals("synthetic", paths.first(), "custom edges come first")
        require(paths.contains("real")) { "reflected properties survive: $paths" }

        subject.graphPath("synthetic")!!.set(42f)
        requireEquals(42f, subject.hidden, "custom setter writes through")
        println("  ok: LiveGraphInspectable edges merge ahead of reflection")
    }

    private fun testPolicyKnobs() {
        val v = voice()
        requireEquals(
            listOf("carrier", "modulator"),
            v.graphPaths("**", GraphWalkOptions(maxDepth = 0)).paths().take(2),
            "maxDepth clips"
        )
        require(v.graphPaths("**", GraphWalkOptions(maxRefs = 3)).size <= 3) { "maxRefs bounds" }

        // Pruning keeps the built-in guards, so no reflection into String.
        val shallow = v.graphPaths(
            "**",
            GraphWalkOptions(descendInto = { value, byDefault -> byDefault && value !is Osc })
        )
        requireEquals(listOf("carrier", "modulator", "name"), shallow.paths(), "descendInto prunes")

        class Maybe(var value: Float?, var other: Float)
        requireEquals(
            listOf("other"),
            Maybe(null, 1f).graphPaths("**", GraphWalkOptions(includeNulls = false)).paths(),
            "includeNulls filters"
        )
        println("  ok: maxDepth, maxRefs, descendInto and includeNulls")
    }

    private fun testHtmlRendering() {
        val html = voice().graphPaths("**").toHTML()
        require(html.contains("PathSet") && html.contains("<table")) { "PathSet renders a table" }

        val hostile = mutableMapOf<String, Any?>("<script>" to 1f).graphPaths("**").toHTML()
        require(hostile.contains("&lt;script&gt;") && !hostile.contains("<script>")) {
            "markup must be escaped: $hostile"
        }
        println("  ok: HTML rendering and escaping")
    }
}
