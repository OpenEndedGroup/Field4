package afield.data.graph

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A* with zero, straight-line and landmark (ALT) heuristics against plain Dijkstra. */
class LandmarkAStarTest {

    private fun randomGraph(rng: Random, n: Int, extra: Int): PointGraph {
        val edges = mutableSetOf<GraphEdge>()
        // a random spanning tree keeps it connected, then extra random edges
        for (i in 1 until n) edges += GraphEdge(rng.nextInt(i), i, 0.5 + rng.nextDouble() * 4.0)
        repeat(extra) {
            val a = rng.nextInt(n)
            val b = rng.nextInt(n)
            if (a != b) edges += GraphEdge(a, b, 0.5 + rng.nextDouble() * 4.0)
        }
        return PointGraph(edges.toList(), n)
    }

    @Test
    fun aStarLengthEqualsDijkstraOnRandomGraphs() {
        val rng = Random(17)
        repeat(40) {
            val g = randomGraph(rng, 60, 90)
            val lm = graphFarthestPointLandmarks(g, 4, first = rng.nextInt(60))
            repeat(10) {
                val s = rng.nextInt(60)
                val t = rng.nextInt(60)
                val exact = graphDijkstraDistances(g, s)[t]
                val plain = graphAStarSearch(g, s, t)
                val alt = lm.search(s, t)
                assertEquals(exact, plain.path!!.totalWeight, 1e-9)
                assertEquals(exact, alt.path!!.totalWeight, 1e-9)
                assertEquals(exact, alt.path!!.edges.sumOf { it.weight }, 1e-9)
            }
        }
    }

    @Test
    fun landmarkHeuristicIsAdmissible() {
        val rng = Random(3)
        repeat(10) {
            val g = randomGraph(rng, 80, 120)
            val lm = graphFarthestPointLandmarks(g, 6)
            assertEquals(6 * 80, lm.memory)
            repeat(20) {
                val goal = rng.nextInt(80)
                val toGoal = graphDijkstraDistances(g, goal)
                for (n in 0 until 80) assertTrue(lm.heuristic(n, goal) <= toGoal[n] + 1e-9)
            }
        }
    }

    @Test
    fun landmarksExpandFewerNodesOnAGrid() {
        val grid = vec2GridGraph(40, 30) { c, r -> c == 20 && r in 5 until 30 }
        val s = grid.index(2, 15)
        val t = grid.index(37, 15)
        val dijkstra = graphAStarSearch(grid.graph, s, t)
        val one = graphFarthestPointLandmarks(grid.graph, 1, first = grid.index(0, 15)).search(s, t)
        val several = graphFarthestPointLandmarks(grid.graph, 6, first = s).search(s, t)
        assertNotNull(dijkstra.path)
        assertEquals(dijkstra.path!!.totalWeight, one.path!!.totalWeight, 1e-9)
        assertEquals(dijkstra.path!!.totalWeight, several.path!!.totalWeight, 1e-9)
        assertTrue(one.expandedCount < dijkstra.expandedCount, "${one.expandedCount} vs ${dijkstra.expandedCount}")
        assertTrue(several.expandedCount < dijkstra.expandedCount, "${several.expandedCount} vs ${dijkstra.expandedCount}")
        assertEquals(t, dijkstra.expanded.last())
    }

    @Test
    fun wallCellsHaveNoEdgesAndUnreachableGoalGivesNull() {
        val grid = vec2GridGraph(5, 5) { c, _ -> c == 2 }
        assertTrue(grid.graph.edges.none { it.i in grid.blocked || it.j in grid.blocked })
        val r = graphAStarSearch(grid.graph, grid.index(0, 0), grid.index(4, 4))
        assertEquals(null, r.path)
        assertEquals(10, r.expandedCount)
    }
}
