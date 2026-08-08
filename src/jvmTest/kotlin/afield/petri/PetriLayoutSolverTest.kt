package afield.petri

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layout solver, held to its objective rather than to a screenshot.
 *
 * The point of scoring a drawing is that "is this layout good?" stops being a matter of opinion, so
 * these tests ask the questions you would otherwise have to squint at a PNG to answer: does the
 * search beat the single deterministic start it replaced, does nothing overlap, and does the
 * Barnes-Hut path agree with the exact one it approximates.
 */
class PetriLayoutSolverTest {

    /** Dining philosophers — 30 nodes, dense, and the net that exposed the hairball. */
    private fun philosophers(count: Int = 5): Pair<List<String>, List<PetriLayoutSolver.Edge>> {
        val names = mutableListOf<String>()
        fun id(name: String): Int {
            val at = names.indexOf(name)
            if (at >= 0) return at
            names += name
            return names.size - 1
        }
        val edges = LinkedHashSet<PetriLayoutSolver.Edge>()
        fun edge(a: String, b: String) {
            val (i, j) = id(a) to id(b)
            edges += PetriLayoutSolver.Edge(minOf(i, j), maxOf(i, j))
        }
        for (i in 0 until count) id("fork.$i")
        for (i in 0 until count) {
            edge("fork.$i", "grab.$i")
            edge("grab.$i", "holding.$i")
            edge("holding.$i", "eat.$i")
            edge("fork.${(i + 1) % count}", "eat.$i")
            edge("eat.$i", "eating.$i")
            edge("eating.$i", "done.$i")
            edge("done.$i", "fork.$i")
            edge("done.$i", "fork.${(i + 1) % count}")
        }
        return names to edges.toList()
    }

    /** A plain ring, which the deterministic circle start already solves perfectly. */
    private fun ring(count: Int): Pair<List<String>, List<PetriLayoutSolver.Edge>> {
        val names = (0 until count).map { "n$it" }
        val edges = (0 until count).map { PetriLayoutSolver.Edge(minOf(it, (it + 1) % count), maxOf(it, (it + 1) % count)) }
        return names to edges
    }

    private fun solve(
        graph: Pair<List<String>, List<PetriLayoutSolver.Edge>>,
        restarts: Int = PetriLayoutSolver.RESTARTS,
        useBarnesHut: Boolean = false,
    ): PetriLayoutSolver.Solution {
        val (names, edges) = graph
        return PetriLayoutSolver.solve(
            count = names.size,
            edges = edges,
            radius = 240.0,
            seed = PetriLayoutSolver.seedFor(names, edges),
            restarts = restarts,
            useBarnesHut = useBarnesHut,
        )
    }

    @Test
    fun `nothing overlaps, whatever the graph`() {
        for (graph in listOf(philosophers(5), philosophers(7), ring(3), ring(16))) {
            val (names, _) = graph
            val solved = solve(graph)
            assertEquals(0, solved.score.overlaps, "overlaps in a ${names.size}-node layout: ${solved.score}")

            // And check the separation invariant directly, not just via the score that reports it.
            for (i in names.indices) for (j in i + 1 until names.size) {
                val d = hypot((solved.x[i] - solved.x[j]).toDouble(), (solved.y[i] - solved.y[j]).toDouble())
                assertTrue(d >= PetriLayoutSolver.MIN_SEPARATION - 0.5, "${names[i]} and ${names[j]} are $d apart")
            }
        }
    }

    @Test
    fun `searching beats the single deterministic start it replaced`() {
        // Restart 0 *is* the old circle-seeded solver, so a one-restart solve is the previous
        // behaviour and the comparison is honest rather than against a straw man.
        val graph = philosophers(5)
        val before = solve(graph, restarts = 1)
        val after = solve(graph)

        println("philosophers(5) single-start: ${before.score}")
        println("philosophers(5) searched:     ${after.score}")
        assertTrue(
            after.score.total <= before.score.total,
            "the search must never be worse than the start it begins from: ${after.score} vs ${before.score}",
        )
        assertTrue(
            after.score.crossings < before.score.crossings,
            "the search should remove crossings on a dense net: ${after.score} vs ${before.score}",
        )
    }

    @Test
    fun `a ring is drawn without a single crossing`() {
        for (n in listOf(4, 8, 16, 24)) {
            val solved = solve(ring(n))
            assertEquals(0, solved.score.crossings, "a $n-ring should draw flat: ${solved.score}")
        }
    }

    @Test
    fun `the same net always produces the same drawing`() {
        val graph = philosophers(5)
        val a = solve(graph)
        val b = solve(graph)
        assertTrue(a.x.contentEquals(b.x) && a.y.contentEquals(b.y), "layout is not reproducible")
        assertEquals(a.score.total, b.score.total)
    }

    @Test
    fun `Barnes-Hut agrees with the exact repulsion it approximates`() {
        // Same seed, same graph, one path through the octree and one through every pair. They will
        // not be bit-identical — that is the whole nature of an approximation — but they must reach
        // drawings of comparable quality, or the fast path is not the same algorithm.
        val graph = philosophers(6)
        val exact = solve(graph, useBarnesHut = false)
        val approx = solve(graph, useBarnesHut = true)

        println("philosophers(6) exact:      ${exact.score}")
        println("philosophers(6) barnes-hut: ${approx.score}")
        assertEquals(0, approx.score.overlaps, "the approximation must still separate: ${approx.score}")
        assertTrue(
            approx.score.total <= exact.score.total * 1.6 + 40.0,
            "barnes-hut quality diverged from exact: ${approx.score} vs ${exact.score}",
        )
    }

    @Test
    fun `dining philosophers is drawn perfectly`() {
        // The net that started all of this: 30 nodes that the plain spring model turned into a
        // hairball. Nothing crossing, nothing overlapping, nothing grazing an edge.
        val solved = solve(philosophers(5))
        assertEquals(0, solved.score.crossings, "${solved.score}")
        assertEquals(0, solved.score.overlaps, "${solved.score}")
        assertEquals(0, solved.score.nodeOnEdge, "${solved.score}")
    }

    @Test
    fun `the work budget bounds a net far larger than any real one`() {
        // Not a wall-clock assertion, which would be flaky — this pins that the budget *binds* at
        // all. Before it existed this graph ran the hill-climb for 269 seconds; the guard is that
        // it now returns, having spent a fixed amount of counted work, with the separation
        // invariant still intact however little search it managed.
        val solved = solve(philosophers(20))
        assertEquals(0, solved.score.overlaps, "separation must survive a truncated search: ${solved.score}")
    }

    @Test
    fun `the real drawing pipeline lays philosophers out without a crossing`() {
        // Build the actual net through the DSL and score the positions `frame()` really produced,
        // rather than a hand-built graph that might not match what PetriVisual feeds the solver.
        val net = petri {
            conflict = PetriConflict.ORDER
            grid = cyc(1, 4)
            for (i in 0 until 5) place("fork.$i", tokens = 1)
            for (i in 0 until 5) {
                val left = i
                val right = (i + 1) % 5
                t("grab.$i") { from("fork.$left"); to("holding.$i") }
                t("eat.$i") { from("holding.$i"); from("fork.$right"); to("eating.$i") }
                t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
            }
        }
        val t0 = System.nanoTime()
        val positions = net.frame().positions
        val ms = (System.nanoTime() - t0) / 1e6
        val names = positions.keys.toList()
        val index = names.withIndex().associate { (i, n) -> n to i }
        val x = FloatArray(names.size) { positions.getValue(names[it]).x.toFloat() }
        val y = FloatArray(names.size) { positions.getValue(names[it]).y.toFloat() }
        val edges = LinkedHashSet<PetriLayoutSolver.Edge>()
        for (tr in net.transitions) {
            val ti = index.getValue(tr.name)
            for (a in tr.inputs) index[a.place]?.let { edges += PetriLayoutSolver.Edge(minOf(it, ti), maxOf(it, ti)) }
            for (a in tr.outputs) index[a.place]?.let { edges += PetriLayoutSolver.Edge(minOf(it, ti), maxOf(it, ti)) }
        }
        val score = PetriLayoutSolver.score(x, y, names.size, edges.toList())
        println("real pipeline n=${names.size} e=${edges.size} ${"%.0f".format(ms)}ms -> $score")

        // The end-to-end assertion, and the one that matters: an isolated solver test can pass on a
        // hand-built graph whose node order — and therefore whose seed — is not the one PetriVisual
        // actually produces. This asks the real path.
        assertEquals(0, score.crossings, "the drawn philosophers should have no crossings: $score")
        assertEquals(0, score.overlaps, "$score")

        // Layout is a pure function of the net's shape, and an animation builds a drawing per
        // frame, so the second call must come from the cache rather than re-running the search.
        val t1 = System.nanoTime()
        val again = net.frame().positions
        val cachedMs = (System.nanoTime() - t1) / 1e6
        assertEquals(positions, again, "a cached layout must be the same layout")
        assertTrue(cachedMs < ms / 10.0, "second frame took ${cachedMs}ms against ${ms}ms — the layout cache is not working")
    }

    @Test
    fun `scoring counts a known crossing and a known clean case`() {
        // An explicit X: two edges whose endpoints alternate around a square.
        val x = floatArrayOf(-100f, 100f, -100f, 100f)
        val y = floatArrayOf(-100f, 100f, 100f, -100f)
        val crossed = listOf(PetriLayoutSolver.Edge(0, 1), PetriLayoutSolver.Edge(2, 3))
        assertEquals(1, PetriLayoutSolver.score(x, y, 4, crossed).crossings)

        // The same four corners joined down the sides instead: no crossing.
        val parallel = listOf(PetriLayoutSolver.Edge(0, 2), PetriLayoutSolver.Edge(3, 1))
        assertEquals(0, PetriLayoutSolver.score(x, y, 4, parallel).crossings)
    }

    @Test
    fun `scoring notices an edge running under an unrelated node`() {
        // Node 2 sits exactly on the segment from 0 to 1.
        val x = floatArrayOf(-200f, 200f, 0f)
        val y = floatArrayOf(0f, 0f, 0f)
        val edges = listOf(PetriLayoutSolver.Edge(0, 1))
        assertEquals(1, PetriLayoutSolver.score(x, y, 3, edges).nodeOnEdge)

        // Moved well clear of it, it is no longer in the way.
        val clear = floatArrayOf(0f, 0f, 400f)
        assertEquals(0, PetriLayoutSolver.score(x, clear, 3, edges).nodeOnEdge)
    }
}
