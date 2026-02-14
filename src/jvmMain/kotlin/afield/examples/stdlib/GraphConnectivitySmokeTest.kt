package afield.examples.stdlib

import afield.stdlib.*
import kotlin.math.abs
import kotlin.random.Random

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
        
        // Gabriel graph should include edges of the square
        require(graph.edges.size == 4) { "Square should have 4 Gabriel edges, got ${graph.edges.size}" }
        
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
            vec(0.0, 1.0),
            vec(1.0, 1.0),
            vec(0.5, 0.5)
        )
        
        val mst = vec2MinimumSpanningTree(points)
        
        require(mst.pointCount == 5) { "Expected 5 points" }
        require(mst.edges.size == 4) { "MST of 5 points should have 4 edges, got ${mst.edges.size}" }
        
        // MST should be connected - check that sum of degrees is 2*(n-1)
        val totalDegree = (0 until mst.pointCount).sumOf { mst.degree(it) }
        require(totalDegree == 8) { "MST total degree should be 8 (2*4), got $totalDegree" }
        
        println("  ✓ 2D Minimum Spanning Tree test passed")
    }

    private fun test3DMinimumSpanningTree() {
        val points = listOf(
            vec(0.0, 0.0, 0.0),
            vec(1.0, 0.0, 0.0),
            vec(0.0, 1.0, 0.0),
            vec(0.0, 0.0, 1.0),
            vec(0.5, 0.5, 0.5)
        )
        
        val mst = vec3MinimumSpanningTree(points)
        
        require(mst.pointCount == 5) { "Expected 5 points" }
        require(mst.edges.size == 4) { "MST of 5 points should have 4 edges, got ${mst.edges.size}" }
        
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
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.0, 1.0),
            vec(1.0, 1.0)
        )
        
        val urquhart = vec2UrquhartGraph(points)
        
        require(urquhart.pointCount == 4) { "Expected 4 points" }
        require(urquhart.edges.isNotEmpty()) { "Urquhart graph should have edges" }
        
        // Urquhart is based on MST, so should have n-1 edges
        require(urquhart.edges.size == 3) { "Urquhart (MST-based) should have 3 edges, got ${urquhart.edges.size}" }
        
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
}
