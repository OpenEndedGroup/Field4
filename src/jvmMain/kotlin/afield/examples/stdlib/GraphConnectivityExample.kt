package afield.examples.stdlib

import afield.stdlib.*
import kotlin.random.Random

/**
 * Example demonstrating the graph connectivity library usage.
 * 
 * This example shows how to use various graph algorithms on 2D and 3D point sets.
 */
object GraphConnectivityExample {
    
    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(60))
        println("Graph Connectivity Library Examples")
        println("=".repeat(60))
        
        example1_BasicKNN()
        example2_MinimumSpanningTree()
        example3_GabrielGraph()
        example4_ComparingGraphs()
        example5_3DPointCloud()
        example6_BetaSkeleton()
        
        println("\n" + "=".repeat(60))
        println("All examples completed!")
        println("=".repeat(60))
    }
    
    private fun example1_BasicKNN() {
        println("\n--- Example 1: K-Nearest Neighbors Graph ---")
        
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.5, 0.5),
            vec(1.0, 1.0),
            vec(0.0, 1.0)
        )
        
        val knnGraph = vec2KnnGraph(points, k = 2)
        
        println("Points: ${knnGraph.pointCount}")
        println("Edges: ${knnGraph.edges.size}")
        
        for (i in 0 until knnGraph.pointCount) {
            val neighbors = knnGraph.neighbors(i)
            println("Point $i neighbors: $neighbors (degree: ${knnGraph.degree(i)})")
        }
    }
    
    private fun example2_MinimumSpanningTree() {
        println("\n--- Example 2: Minimum Spanning Tree ---")
        
        val rnd = Random(42)
        val points = List(10) { 
            vec(rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-5.0, 5.0))
        }
        
        val mst = vec2MinimumSpanningTree(points)
        
        println("Points: ${mst.pointCount}")
        println("MST edges: ${mst.edges.size} (should be ${points.size - 1})")
        
        val totalWeight = mst.edges.sumOf { it.weight }
        println("Total MST weight: ${"%.3f".format(totalWeight)}")
        
        // Verify MST is connected
        val totalDegree = (0 until mst.pointCount).sumOf { mst.degree(it) }
        println("Total degree: $totalDegree (should be ${2 * (points.size - 1)})")
    }
    
    private fun example3_GabrielGraph() {
        println("\n--- Example 3: Gabriel Graph (Square) ---")
        
        val square = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(1.0, 1.0),
            vec(0.0, 1.0)
        )
        
        val gabriel = vec2GabrielGraph(square)
        
        println("Points: ${gabriel.pointCount}")
        println("Gabriel edges: ${gabriel.edges.size}")
        
        println("\nEdges:")
        for (edge in gabriel.edges) {
            println("  ${edge.i} -- ${edge.j}: weight = ${"%.3f".format(edge.weight)}")
        }
    }
    
    private fun example4_ComparingGraphs() {
        println("\n--- Example 4: Comparing Different Graphs ---")
        
        val rnd = Random(123)
        val points = List(20) { 
            vec(rnd.nextDouble(0.0, 10.0), rnd.nextDouble(0.0, 10.0))
        }
        
        val complete = vec2CompleteGraph(points)
        val gabriel = vec2GabrielGraph(points)
        val rng = vec2RelativeNeighborhoodGraph(points)
        val mst = vec2MinimumSpanningTree(points)
        val knn5 = vec2KnnGraph(points, k = 5)
        
        println("Graph sparsity comparison (20 points):")
        println("  Complete Graph:      ${complete.edges.size} edges")
        println("  Gabriel Graph:       ${gabriel.edges.size} edges")
        println("  RNG:                 ${rng.edges.size} edges")
        println("  MST:                 ${mst.edges.size} edges")
        println("  5-NN Graph:          ${knn5.edges.size} edges")
        
        println("\nAverage degree:")
        fun avgDegree(graph: PointGraph) = 
            (0 until graph.pointCount).map { graph.degree(it) }.average()
        
        println("  Complete Graph:      ${"%.2f".format(avgDegree(complete))}")
        println("  Gabriel Graph:       ${"%.2f".format(avgDegree(gabriel))}")
        println("  RNG:                 ${"%.2f".format(avgDegree(rng))}")
        println("  MST:                 ${"%.2f".format(avgDegree(mst))}")
        println("  5-NN Graph:          ${"%.2f".format(avgDegree(knn5))}")
    }
    
    private fun example5_3DPointCloud() {
        println("\n--- Example 5: 3D Point Cloud Connectivity ---")
        
        val rnd = Random(456)
        val cloud = List(15) {
            vec(
                rnd.nextDouble(-3.0, 3.0),
                rnd.nextDouble(-3.0, 3.0),
                rnd.nextDouble(-3.0, 3.0)
            )
        }
        
        val knn3d = vec3KnnGraph(cloud, k = 4)
        val gabriel3d = vec3GabrielGraph(cloud)
        val mst3d = vec3MinimumSpanningTree(cloud)
        
        println("3D point cloud (${cloud.size} points):")
        println("  4-NN Graph:          ${knn3d.edges.size} edges")
        println("  Gabriel Graph:       ${gabriel3d.edges.size} edges")
        println("  MST:                 ${mst3d.edges.size} edges")
        
        // Analyze MST structure
        val leafNodes = (0 until mst3d.pointCount).count { mst3d.degree(it) == 1 }
        val internalNodes = (0 until mst3d.pointCount).count { mst3d.degree(it) > 2 }
        println("\nMST structure:")
        println("  Leaf nodes:          $leafNodes")
        println("  Internal nodes (>2): $internalNodes")
    }
    
    private fun example6_BetaSkeleton() {
        println("\n--- Example 6: Beta-Skeleton Progression ---")
        
        val points = listOf(
            vec(0.0, 0.0),
            vec(1.0, 0.0),
            vec(0.5, 0.8),
            vec(1.5, 0.5)
        )
        
        println("Beta-skeleton edge counts for 4 points:")
        
        val betaValues = listOf(0.5, 1.0, 1.5, 2.0, 3.0)
        for (beta in betaValues) {
            val skeleton = vec2BetaSkeleton(points, beta = beta)
            println("  beta = ${"%.1f".format(beta)}: ${skeleton.edges.size} edges")
        }
        
        println("\nNote:")
        println("  beta = 1.0 ≈ Gabriel graph")
        println("  beta = 2.0 ≈ RNG")
        println("  Higher beta → sparser graph")
    }
}
