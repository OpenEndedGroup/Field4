package afield.examples.stdlib

import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.abs
import kotlin.random.Random

/*
FIELD_TEST
id: stdlib.graph-connectivity.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.GraphConnectivitySmokeTest
tags: [smoke, stdlib, graph, geometry]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Graph Connectivity smoke tests passed"
*/
/**
 * Smoke tests for the graph connectivity library.
 *
 * Tests various graph algorithms on 2D and 3D point sets.
 */
object GraphConnectivitySmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running Graph Connectivity smoke tests...")
            test2DKnnGraph()
            test3DKnnGraph()
            test2DOrthantGraph()
            test3DOrthantGraph()
            test2DRelativeNeighborhoodGraph()
            test3DRelativeNeighborhoodGraph()
            test2DGabrielGraph()
            test3DGabrielGraph()
            test2DBetaSkeleton()
            test3DBetaSkeleton()
            test2DMinimumSpanningTree()
            test3DMinimumSpanningTree()
            test2DMaximumDiameterGraph()
            test3DMaximumDiameterGraph()
            test2DNearestNeighborGraph()
            test3DNearestNeighborGraph()
            test2DUrquhartGraph()
            test2DDelaunayGraph()
            test2DMaximumCutGraph()
            test3DMaximumCutGraph()
            test2DCompleteGraph()
            test3DCompleteGraph()
            testGraphStructure()
            testEdgeSymmetry()
            testVoronoiDiagram()
            testDelaunayTriangulation()
            testDelaunayTriangles()
            testConvexHull()
            testConvexHullGraph()
            testHullProjectionGraph()
            testCustomDistanceMetric()
            testSearchAlgorithms()
            testEdgeVisitStateAndGreedyPaths()
            testGraphDiameterDecomposition()
            testFLineGraph()
            println("✅ Graph Connectivity smoke tests passed")
            true
        } catch (t: Throwable) {
            println("❌ Graph Connectivity smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Graph Connectivity smoke tests failed" }
    }

    private fun test2DKnnGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0),
            vec(0.5, 0.5)
        )
        
        val graph = vec2KnnGraph(points, k = 2)
        
        require(graph.pointCount == 5) { "Expected 5 points, got ${graph.pointCount}" }
        require(graph.edges.isNotEmpty()) { "KNN graph should have edges" }
        
        // Each point should have approximately k neighbors (edges are undirected)
        for (i in 0 until graph.pointCount) {
            val degree = graph.degree(i)
            require(degree >= 1) { "Point $i has degree $degree, expected at least 1" }
        }
        
        // All edges should have positive weights
        require(graph.edges.all { it.weight > 0.0 }) { "All edges should have positive weights" }
        
        println("  ✓ 2D KNN graph test passed")
    }

    private fun test3DKnnGraph() {
        val rnd = Random(42)
        val points = List(20) {
            vec(
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0),
                rnd.nextDouble(-10.0, 10.0)
            )
        }
        
        val graph = vec3KnnGraph(points, k = 3)
        
        require(graph.pointCount == 20) { "Expected 20 points, got ${graph.pointCount}" }
        require(graph.edges.isNotEmpty()) { "KNN graph should have edges" }
        require(graph.edges.all { it.weight > 0.0 && it.weight.isFinite() }) {
            "All edges should have positive, finite weights"
        }
        
        println("  ✓ 3D KNN graph test passed")
    }

    private fun test2DOrthantGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 1.0),
            vec(-1.0, 1.0),
            vec(1.0, -1.0),
            vec(-1.0, -1.0)
        )
        
        val graph = vec2OrthantGraph(points)
        
        require(graph.pointCount == 5) { "Expected 5 points" }
        require(graph.edges.isNotEmpty()) { "Orthant graph should have edges" }
        
        // Center point should connect to all four orthants
        val centerDegree = graph.degree(0)
        require(centerDegree == 4) { "Center point should have 4 neighbors (one per orthant), got $centerDegree" }
        
        println("  ✓ 2D Orthant graph test passed")
    }

    private fun test3DOrthantGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 1.0, 1.0),
            vec(-1.0, 1.0, 1.0),
            vec(1.0, -1.0, 1.0),
            vec(-1.0, -1.0, 1.0),
            vec(1.0, 1.0, -1.0),
            vec(-1.0, 1.0, -1.0),
            vec(1.0, -1.0, -1.0),
            vec(-1.0, -1.0, -1.0)
        )
        
        val graph = vec3OrthantGraph(points)
        
        require(graph.pointCount == 9) { "Expected 9 points" }
        require(graph.edges.isNotEmpty()) { "Orthant graph should have edges" }
        
        // Center point should connect to all eight octants
        val centerDegree = graph.degree(0)
        require(centerDegree == 8) { "Center point should have 8 neighbors (one per octant), got $centerDegree" }
        
        println("  ✓ 3D Orthant graph test passed")
    }

    private fun test2DRelativeNeighborhoodGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.5, 0.5),
            vec(2.0, 0.0)
        )
        
        val graph = vec2RelativeNeighborhoodGraph(points)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        require(graph.edges.isNotEmpty()) { "RNG should have edges" }
        
        // RNG should be a connected graph (or close to it)
        // At minimum, we should have some edges
        require(graph.edges.size >= 2) { "RNG should have at least 2 edges for 4 points" }
        
        println("  ✓ 2D Relative Neighborhood graph test passed")
    }

    private fun test3DRelativeNeighborhoodGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0)
        )
        
        val graph = vec3RelativeNeighborhoodGraph(points)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        require(graph.edges.isNotEmpty()) { "RNG should have edges" }
        
        println("  ✓ 3D Relative Neighborhood graph test passed")
    }

    private fun test2DGabrielGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0)
        )
        
        val graph = vec2GabrielGraph(points)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        require(graph.edges.isNotEmpty()) { "Gabriel graph should have edges" }
        
        val undirectedEdges = graph.edges
            .map { minOf(it.i, it.j) to maxOf(it.i, it.j) }
            .toSet()

        // With the open-circle Gabriel definition, the square's diagonals are retained because the
        // opposite corners lie on the boundary of the diameter circle rather than strictly inside it.
        require(graph.edges.size == 6) { "Square should have 6 Gabriel edges under open-circle semantics, got ${graph.edges.size}" }
        require((0 to 1) in undirectedEdges) { "Expected square edge 0-1" }
        require((0 to 2) in undirectedEdges) { "Expected square edge 0-2" }
        require((1 to 3) in undirectedEdges) { "Expected square edge 1-3" }
        require((2 to 3) in undirectedEdges) { "Expected square edge 2-3" }
        
        println("  ✓ 2D Gabriel graph test passed")
    }

    private fun test3DGabrielGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0)
        )
        
        val graph = vec3GabrielGraph(points)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        require(graph.edges.isNotEmpty()) { "Gabriel graph should have edges" }
        
        println("  ✓ 3D Gabriel graph test passed")
    }

    private fun test2DBetaSkeleton() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        // Test beta = 1.0 (Gabriel graph)
        val gabriel = vec2BetaSkeleton(points, beta = 1.0)
        require(gabriel.pointCount == 3) { "Expected 3 points" }
        require(gabriel.edges.size == 3) { "Triangle should have 3 Gabriel edges" }
        
        // Test beta = 2.0 (closer to RNG)
        val rng = vec2BetaSkeleton(points, beta = 2.0)
        require(rng.pointCount == 3) { "Expected 3 points" }
        require(rng.edges.isNotEmpty()) { "Beta-skeleton should have edges" }
        
        println("  ✓ 2D Beta-skeleton test passed")
    }

    private fun test3DBetaSkeleton() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0)
        )
        
        val skeleton = vec3BetaSkeleton(points, beta = 1.0)
        
        require(skeleton.pointCount == 4) { "Expected 4 points" }
        require(skeleton.edges.isNotEmpty()) { "Beta-skeleton should have edges" }
        
        println("  ✓ 3D Beta-skeleton test passed")
    }

    private fun test2DMinimumSpanningTree() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(10.0, 0.0),
            vec(11.0, 0.0)
        )

        val mst = vec2MinimumSpanningTree(points)

        require(mst.pointCount == 4) { "Expected 4 points" }
        require(mst.edges.size == 3) { "MST of 4 points should have 3 edges, got ${mst.edges.size}" }

        // 1-NN is disconnected for these two tight pairs, so MST must grow KNN connectivity.
        require(mst.edges.any { setOf(it.i, it.j) == setOf(1, 2) }) {
            "Adaptive KNN MST should include the shortest bridge between the two pairs"
        }

        // MST should be connected - check that sum of degrees is 2*(n-1)
        val totalDegree = (0 until mst.pointCount).sumOf { mst.degree(it) }
        require(totalDegree == 6) { "MST total degree should be 6 (2*3), got $totalDegree" }

        println("  ✓ 2D Minimum Spanning Tree test passed")
    }

    private fun test3DMinimumSpanningTree() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(10.0, 0.0, 0.0),
            vec(11.0, 0.0, 0.0)
        )

        val mst = vec3MinimumSpanningTree(points)

        require(mst.pointCount == 4) { "Expected 4 points" }
        require(mst.edges.size == 3) { "MST of 4 points should have 3 edges, got ${mst.edges.size}" }
        require(mst.edges.any { setOf(it.i, it.j) == setOf(1, 2) }) {
            "Adaptive KNN MST should include the shortest bridge between the two 3D pairs"
        }

        println("  ✓ 3D Minimum Spanning Tree test passed")
    }

    private fun test2DMaximumDiameterGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(2.0, 0.0),
            vec(10.0, 0.0)
        )
        
        val graph = vec2MaximumDiameterGraph(points, maxDiameter = 1.5)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        
        // Should connect first 3 points (distances <= 1.5) but not the last one
        require(graph.edges.size == 2) { "Should have 2 edges (0-1, 1-2), got ${graph.edges.size}" }
        require(graph.edges.all { it.weight <= 1.5 + 1e-10 }) {
            "All edges should be within diameter"
        }
        
        println("  ✓ 2D Maximum Diameter graph test passed")
    }

    private fun test3DMaximumDiameterGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(10.0, 10.0, 10.0)
        )
        
        val graph = vec3MaximumDiameterGraph(points, maxDiameter = 2.0)
        
        require(graph.pointCount == 4) { "Expected 4 points" }
        require(graph.edges.all { it.weight <= 2.0 + 1e-10 }) {
            "All edges should be within diameter"
        }
        
        println("  ✓ 3D Maximum Diameter graph test passed")
    }

    private fun test2DNearestNeighborGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0)
        )
        
        val nng = vec2NearestNeighborGraph(points)
        
        require(nng.pointCount == 4) { "Expected 4 points" }
        require(nng.edges.isNotEmpty()) { "Nearest neighbor graph should have edges" }
        
        // Each point connects to at least its nearest neighbor
        for (i in 0 until nng.pointCount) {
            require(nng.degree(i) >= 1) { "Each point should have at least 1 neighbor" }
        }
        
        println("  ✓ 2D Nearest Neighbor graph test passed")
    }

    private fun test3DNearestNeighborGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0)
        )
        
        val nng = vec3NearestNeighborGraph(points)
        
        require(nng.pointCount == 4) { "Expected 4 points" }
        require(nng.edges.isNotEmpty()) { "Nearest neighbor graph should have edges" }
        
        println("  ✓ 3D Nearest Neighbor graph test passed")
    }

    private fun test2DUrquhartGraph() {
        val points = listOf(
            vec(1.0, 0.0),
            vec(0.30901699437494745, 0.9510565162951535),
            vec(-0.8090169943749473, 0.5877852522924732),
            vec(-0.8090169943749476, -0.587785252292473),
            vec(0.30901699437494723, -0.9510565162951536)
        )

        val urquhart = vec2UrquhartGraph(points)
        val mst = vec2MinimumSpanningTree(points)

        require(urquhart.pointCount == 5) { "Expected 5 points" }
        require(urquhart.edges.isNotEmpty()) { "Urquhart graph should have edges" }

        // For a convex pentagon, Urquhart keeps the hull cycle while the MST has n-1 edges.
        require(mst.edges.size == 4) { "MST of 5 points should have 4 edges, got ${mst.edges.size}" }
        require(urquhart.edges.size == 5) { "Urquhart graph should have 5 hull edges, got ${urquhart.edges.size}" }
        require(urquhart.edges.size > mst.edges.size) { "Urquhart graph should be strictly larger than MST here" }

        println("  ✓ 2D Urquhart graph test passed")
    }

    private fun test2DDelaunayGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        val delaunay = vec2DelaunayGraph(points)
        
        require(delaunay.pointCount == 3) { "Expected 3 points" }
        require(delaunay.edges.size == 3) { "Triangle Delaunay should have 3 edges" }
        
        println("  ✓ 2D Delaunay graph test passed")
    }

    private fun test2DMaximumCutGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(2.0, 0.0),
            vec(3.0, 0.0)
        )
        
        val maxCut = vec2MaximumCutGraph(points)
        
        require(maxCut.pointCount == 4) { "Expected 4 points" }
        require(maxCut.edges.isNotEmpty()) { "Max cut graph should have edges" }
        
        // Should partition into two sets and connect between them
        // With 4 points in 2 partitions, we expect cross-partition edges
        require(maxCut.edges.size >= 2) { "Max cut should have at least some cross-partition edges" }
        
        println("  ✓ 2D Maximum Cut graph test passed")
    }

    private fun test3DMaximumCutGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(2.0, 0.0, 0.0),
            vec(3.0, 0.0, 0.0)
        )
        
        val maxCut = vec3MaximumCutGraph(points)
        
        require(maxCut.pointCount == 4) { "Expected 4 points" }
        require(maxCut.edges.isNotEmpty()) { "Max cut graph should have edges" }
        
        println("  ✓ 3D Maximum Cut graph test passed")
    }

    private fun test2DCompleteGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        val complete = vec2CompleteGraph(points)
        
        require(complete.pointCount == 3) { "Expected 3 points" }
        
        // Complete graph with n points has n*(n-1)/2 edges
        val expectedEdges = 3 * 2 / 2
        require(complete.edges.size == expectedEdges) {
            "Complete graph of 3 points should have $expectedEdges edges, got ${complete.edges.size}"
        }
        
        // All points should be fully connected
        for (i in 0 until complete.pointCount) {
            require(complete.degree(i) == 2) { "Each point in complete graph should have degree 2" }
        }
        
        println("  ✓ 2D Complete graph test passed")
    }

    private fun test3DCompleteGraph() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0)
        )
        
        val complete = vec3CompleteGraph(points)
        
        require(complete.pointCount == 4) { "Expected 4 points" }
        
        // Complete graph with 4 points has 6 edges
        require(complete.edges.size == 6) {
            "Complete graph of 4 points should have 6 edges, got ${complete.edges.size}"
        }
        
        println("  ✓ 3D Complete graph test passed")
    }

    private fun testGraphStructure() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        val graph = vec2KnnGraph(points, k = 2)
        
        // Test PointGraph methods
        val neighbors0 = graph.neighbors(0)
        require(neighbors0.isNotEmpty()) { "Point 0 should have neighbors" }
        require(neighbors0.all { it in 0 until graph.pointCount }) {
            "All neighbor indices should be valid"
        }
        
        val degree0 = graph.degree(0)
        require(degree0 == neighbors0.size) { "Degree should match neighbors count" }
        
        println("  ✓ Graph structure test passed")
    }

    private fun testEdgeSymmetry() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        val graph = vec2MinimumSpanningTree(points)
        
        // Edges should be symmetric: if (i,j) is in the graph, both points should see each other
        for (edge in graph.edges) {
            val neighbors_i = graph.neighbors(edge.i)
            val neighbors_j = graph.neighbors(edge.j)
            
            require(edge.j in neighbors_i) {
                "Edge (${ edge.i},${ edge.j}) exists but ${ edge.j} not in neighbors of ${ edge.i}"
            }
            require(edge.i in neighbors_j) {
                "Edge (${ edge.i},${ edge.j}) exists but ${ edge.i} not in neighbors of ${ edge.j}"
            }
        }
        
        println("  ✓ Edge symmetry test passed")
    }

    private fun testVoronoiDiagram() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.5, 1.0),
            vec(0.5, 0.5)
        )
        
        val voronoi = vec2VoronoiDiagram(points)
        
        require(voronoi.pointCount == 4) { "Expected 4 points" }
        require(voronoi.cells.isNotEmpty()) { "Voronoi diagram should have cells" }
        
        // Each cell should have vertices
        for (cell in voronoi.cells) {
            require(cell.vertices.isNotEmpty()) { "Cell should have vertices" }
            require(cell.siteIndex in 0 until points.size) { "Site index should be valid" }
        }
        
        println("  ✓ Voronoi diagram test passed")
    }

    private fun testDelaunayTriangulation() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0)
        )
        
        val delaunay = vec2DelaunayGraph(points)
        
        require(delaunay.pointCount == 4) { "Expected 4 points" }
        require(delaunay.edges.isNotEmpty()) { "Delaunay graph should have edges" }
        
        // Delaunay should have more edges than MST but fewer than complete
        val mst = vec2MinimumSpanningTree(points)
        require(delaunay.edges.size >= mst.edges.size) {
            "Delaunay should have at least as many edges as MST"
        }
        
        println("  ✓ Delaunay triangulation test passed")
    }

    private fun testDelaunayTriangles() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0)
        )
        
        val triangles = vec2DelaunayTriangles(points)
        
        require(triangles.isNotEmpty()) { "Should have triangles" }
        
        // Verify triangle indices are valid
        for ((i, j, k) in triangles) {
            require(i in 0 until points.size) { "Triangle vertex i out of bounds" }
            require(j in 0 until points.size) { "Triangle vertex j out of bounds" }
            require(k in 0 until points.size) { "Triangle vertex k out of bounds" }
            require(i != j && j != k && i != k) { "Triangle vertices should be distinct" }
        }
        
        println("  ✓ Delaunay triangles test passed")
    }

    private fun testConvexHull() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(1.0, 1.0),
            vec(0.0, 1.0),
            vec(0.5, 0.5)  // Interior point
        )
        
        val hull = vec2ConvexHull(points)
        
        require(hull.hullPoints.size == 4) { "Square hull should have 4 vertices" }
        require(hull.hullIndices.size == 4) { "Should have 4 hull indices" }
        require(4 !in hull.hullIndices) { "Interior point should not be on hull" }
        
        println("  ✓ Convex hull test passed")
    }

    private fun testConvexHullGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(1.0, 1.0),
            vec(0.0, 1.0),
            vec(0.5, 0.5)
        )
        
        val hullGraph = vec2ConvexHullGraph(points)
        
        require(hullGraph.pointCount == 5) { "Expected 5 points" }
        require(hullGraph.edges.size == 4) { "Square hull should have 4 edges" }
        
        println("  ✓ Convex hull graph test passed")
    }

    private fun testHullProjectionGraph() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(1.0, 1.0),
            vec(0.0, 1.0),
            vec(0.5, 0.5)  // Interior point
        )
        
        val projection = vec2HullProjectionGraph(points)
        
        require(projection.pointCount == 5) { "Expected 5 points" }
        require(projection.edges.size == 1) { "Should have 1 edge from interior to hull" }
        
        // The edge should connect point 4 (interior) to a hull point
        val edge = projection.edges[0]
        require(edge.i == 4 || edge.j == 4) { "Edge should involve interior point" }
        
        println("  ✓ Hull projection graph test passed")
    }

    private fun testCustomDistanceMetric() {
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0)
        )
        
        // Manhattan distance metric
        val manhattanMetric: (Int, Int, List<*>) -> Double = { i, j, pts ->
            val p = pts as List<Vec2>
            abs(p[i].x - p[j].x).toDouble() + abs(p[i].y - p[j].y).toDouble()
        }
        
        val options = GraphConnectivityOptions(customDistanceMetric = manhattanMetric)
        val knn = vec2KnnGraph(points, k = 1, options)
        
        require(knn.edges.isNotEmpty()) { "KNN with custom metric should have edges" }
        
        // With Manhattan distance, distances should match
        for (edge in knn.edges) {
            val p1 = points[edge.i]
            val p2 = points[edge.j]
            val expectedDist = abs(p1.x - p2.x).toDouble() + abs(p1.y - p2.y).toDouble()
            assertClose("Custom metric distance", edge.weight, expectedDist, 1e-9)
        }
        
        println("  ✓ Custom distance metric test passed")
    }

    private fun testSearchAlgorithms() {
        val graph = PointGraph(
            edges = listOf(
                GraphEdge(0, 1, 1.0),
                GraphEdge(1, 2, 1.0),
                GraphEdge(0, 2, 5.0),
                GraphEdge(2, 3, 1.0),
                GraphEdge(1, 3, 4.0)
            ),
            pointCount = 4
        )

        val bfs = graphBfsPath(graph, start = 0, goal = 3)
        require(bfs != null) { "BFS should find a path from 0 to 3" }
        require(bfs.start == 0 && bfs.end == 3) { "BFS path endpoints are incorrect" }
        require(bfs.nodes.size == 3) { "BFS should find a minimum-hop path of length 2 edges" }

        val dfs = graphDfsPath(graph, start = 0, goal = 3)
        require(dfs != null) { "DFS should find a path from 0 to 3" }
        require(dfs.start == 0 && dfs.end == 3) { "DFS path endpoints are incorrect" }
        require(dfs.edges.isNotEmpty()) { "DFS path should contain edges" }

        val aStar = graphAStarPath(graph, start = 0, goal = 3)
        require(aStar != null) { "A* should find a path from 0 to 3" }
        assertClose("A* total weight", aStar.totalWeight, 3.0, 1e-9)
        require(aStar.nodes == listOf(0, 1, 2, 3)) {
            "A* should return the weighted-shortest path [0,1,2,3], got ${aStar.nodes}"
        }

        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(2.0, 0.0),
            vec(3.0, 0.0)
        )
        val aStarVec2 = vec2AStarPath(graph, points, start = 0, goal = 3)
        require(aStarVec2 != null) { "vec2AStarPath should find a path from 0 to 3" }
        assertClose("vec2 A* total weight", aStarVec2.totalWeight, 3.0, 1e-9)

        println("  ✓ Graph search algorithms test passed")
    }

    private fun testEdgeVisitStateAndGreedyPaths() {
        val graph = PointGraph(
            edges = listOf(
                GraphEdge(0, 1, 1.0),
                GraphEdge(1, 2, 1.0),
                GraphEdge(0, 3, 2.0),
                GraphEdge(3, 4, 2.0)
            ),
            pointCount = 5
        )

        val initialState = graph.edgeVisitState()
        require(initialState.visitedEdges.isEmpty()) { "Initial state should have no visited edges" }
        require(initialState.unvisitedEdges.size == graph.edges.size) {
            "Initial unvisited edges should match graph edges"
        }

        val greedy = graphGreedyClosestUnvisitedPaths(graph, anchor = 0, edgeState = initialState)
        require(greedy.paths.isNotEmpty()) { "Greedy anchored search should produce at least one path" }
        require(greedy.edgeState.visitedEdges.isNotEmpty()) { "Greedy anchored search should visit edges" }
        require(greedy.edgeState.unvisitedEdges.isNotEmpty()) {
            "Expected disconnected leftovers from anchor-based unvisited traversal"
        }
        require(greedy.paths.all { it.start == 0 }) { "All greedy paths should be anchored at node 0" }

        val combined = graphAnchoredGreedyThenDiameterCover(graph, anchor = 0, edgeState = initialState)
        require(combined.edgeState.unvisitedEdges.isEmpty()) {
            "Combined anchored+diameter cover should consume all edges"
        }
        val covered = combined.paths.flatMap { it.edges }.toSet()
        require(covered == graph.edges.toSet()) { "Combined cover should include every graph edge exactly once" }

        println("  ✓ Edge visit state and greedy path cover test passed")
    }

    private fun testGraphDiameterDecomposition() {
        val graph = PointGraph(
            edges = listOf(
                GraphEdge(0, 1, 1.0),
                GraphEdge(1, 2, 1.0),
                GraphEdge(2, 3, 1.0),
                GraphEdge(3, 4, 1.0),
                GraphEdge(2, 5, 1.0)
            ),
            pointCount = 6
        )

        val diameter = graphDiameterPath(graph)
        require(diameter != null) { "Diameter path should exist for non-empty graph" }
        assertClose("Diameter length", diameter.totalWeight, 4.0, 1e-9)
        require(diameter.nodes.first() in listOf(0, 4) && diameter.nodes.last() in listOf(0, 4)) {
            "Diameter path should connect endpoints of the long chain, got ${diameter.nodes}"
        }

        val decomposition = graphDiameterPathDecomposition(graph)
        require(decomposition.paths.isNotEmpty()) { "Diameter decomposition should return paths" }
        require(decomposition.edgeState.unvisitedEdges.isEmpty()) {
            "Diameter decomposition should consume all edges"
        }

        val covered = decomposition.paths.flatMap { it.edges }.toSet()
        require(covered == graph.edges.toSet()) { "Diameter decomposition should cover all edges" }

        for (i in 0 until decomposition.paths.lastIndex) {
            require(decomposition.paths[i].totalWeight + 1e-9 >= decomposition.paths[i + 1].totalWeight) {
                "Diameter decomposition should produce long-to-short paths"
            }
        }

        println("  ✓ Graph diameter decomposition test passed")
    }

    private fun testFLineGraph() {
        val a = FLine()
            .moveTo(0.0, 0.0, 0.0)
            .lineTo(1.0, 0.0, 0.0)

        val b = FLine()
            .moveTo(1.04, 0.02, 0.0)
            .lineTo(2.0, 0.0, 0.0)

        val welded = listOf(a, b).toFLineGraph(epsilon = 0.05)
        require(welded.pointCount == 3) {
            "FLine graph should weld nearby shared endpoints into 3 vertices, got ${welded.pointCount}"
        }
        require(welded.edges.size == 2) {
            "FLine graph should preserve the two line segments, got ${welded.edges.size}"
        }
        require(welded.graph.degree(1) == 2) {
            "Welded middle vertex should connect both segments"
        }
        require(welded.vertexSources.any { it.size == 2 }) {
            "Expected one welded vertex to retain sources from both lines"
        }

        val curve = FLine()
            .moveTo(0.0, 0.0, 0.0)
            .cubicTo(
                vec(0.0, 1.0, 0.0),
                vec(1.0, 1.0, 0.0),
                vec(1.0, 0.0, 0.0)
            )
        val curveGraph = curve.toFLineGraph(epsilon = 0.001, cubicFlatness = 0.05)
        require(curveGraph.pointCount > 2) {
            "Curved FLine graph should contain intermediate flattened vertices"
        }
        require(curveGraph.edges.size == curveGraph.pointCount - 1) {
            "Single open curve should produce a chain of edges"
        }

        val isolated = FLine().moveTo(10.0, 10.0, 0.0)
        val isolatedGraph = isolated.toFLineGraph(epsilon = 0.0)
        require(isolatedGraph.pointCount == 1) {
            "Move-only FLine should still produce an isolated graph vertex"
        }
        require(isolatedGraph.edges.isEmpty()) {
            "Move-only FLine should not produce graph edges"
        }

        println("  ✓ FLine graph connectivity test passed")
    }

    private fun assertClose(label: String, got: Double, expected: Double, eps: Double) {
        require(abs(got - expected) <= eps) {
            "$label mismatch: got=$got expected=$expected eps=$eps"
        }
    }
}
