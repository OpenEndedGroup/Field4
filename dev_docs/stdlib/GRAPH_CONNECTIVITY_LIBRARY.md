# Graph Connectivity Library for Field4

A comprehensive self-contained graph library for computing different connectivities over 2D and 3D point sets using JOML Vector2f and Vector3f. Includes full JTS (Java Topology Suite) integration for Delaunay triangulation, Voronoi diagrams, and convex hull operations.

## Location

- **Library**: `src/jvmMain/kotlin/afield/stdlib/graph_connectivity.kt`
- **Tests**: `src/jvmMain/kotlin/afield/examples/stdlib/GraphConnectivitySmokeTest.kt`

## Overview

This library provides 29+ different graph connectivity algorithms and computational geometry operations for spatial point sets, with support for custom distance metrics.

## Core Data Structures

### GraphEdge
```kotlin
data class GraphEdge(val i: Int, val j: Int, val weight: Double)
```
Represents an edge between two points with:
- `i`, `j`: Indices of the connected points
- `weight`: Distance/weight of the edge

### PointGraph
```kotlin
data class PointGraph(val edges: List<GraphEdge>, val pointCount: Int)
```
Represents a graph computed from points with utility methods:
- `neighbors(pointIndex: Int)`: Get all neighbor indices
- `degree(pointIndex: Int)`: Get the degree (neighbor count) of a point

## Algorithms

### 1. K-Nearest Neighbors (KNN) Graph
```kotlin
fun vec2KnnGraph(points: List<Vec2>, k: Int, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3KnnGraph(points: List<Vec3>, k: Int, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Connects each point to its k nearest neighbors.

**Use Cases:**
- Machine learning feature spaces
- Point cloud processing
- Nearest neighbor queries

**Example:**
```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(0.0, 1.0), vec(1.0, 1.0))
val graph = vec2KnnGraph(points, k = 2)
println("Neighbors of point 0: ${graph.neighbors(0)}")
```

### 2. Orthant Neighborhood Graph
```kotlin
fun vec2OrthantGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3OrthantGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Connects points to their nearest neighbors in each orthant (quadrant in 2D, octant in 3D).

**Use Cases:**
- Directional connectivity analysis
- Spatial indexing
- Angular neighborhood queries

**Properties:**
- 2D: Up to 4 neighbors per point (one per quadrant)
- 3D: Up to 8 neighbors per point (one per octant)

### 3. Relative Neighborhood Graph (RNG)
```kotlin
fun vec2RelativeNeighborhoodGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3RelativeNeighborhoodGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Edge (u,v) exists if no other point w satisfies `max(d(u,w), d(v,w)) < d(u,v)`.

**Use Cases:**
- Shape reconstruction
- Pattern recognition
- Proximity analysis

**Properties:**
- Supergraph of MST
- Subgraph of Delaunay triangulation
- Sparser than Gabriel graph

### 4. Gabriel Graph
```kotlin
fun vec2GabrielGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3GabrielGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Edge (u,v) exists if the circle/sphere with diameter uv contains no other points.

**Use Cases:**
- Geographic network design
- Wireless network topology
- Shape reconstruction

**Properties:**
- Subgraph of Delaunay triangulation
- Supergraph of RNG
- Contains MST

### 5. Beta-Skeleton
```kotlin
fun vec2BetaSkeleton(points: List<Vec2>, beta: Double = 1.0, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3BetaSkeleton(points: List<Vec3>, beta: Double = 1.0, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Parameterized family of proximity graphs controlled by beta parameter.

**Beta Values:**
- `beta = 0`: Nearest neighbor graph
- `beta = 1`: Gabriel graph
- `beta = 2`: Relative neighborhood graph
- `beta > 2`: Increasingly sparse graphs

**Use Cases:**
- Multi-scale connectivity analysis
- Adjustable proximity relationships
- Shape skeleton extraction

### 6. Minimum Spanning Tree (MST)
```kotlin
fun vec2MinimumSpanningTree(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3MinimumSpanningTree(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Euclidean minimum spanning tree using Kruskal's algorithm with Union-Find.

**Use Cases:**
- Network design (minimize total cable length)
- Clustering analysis
- Hierarchical structure extraction

**Properties:**
- Exactly `n-1` edges for `n` points
- Minimizes total edge weight
- Always connected (if points are reachable)

### 7. Maximum Diameter Graph
```kotlin
fun vec2MaximumDiameterGraph(points: List<Vec2>, maxDiameter: Double, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3MaximumDiameterGraph(points: List<Vec3>, maxDiameter: Double, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Connects all point pairs within a specified maximum distance.

**Use Cases:**
- Communication range graphs
- Proximity-based clustering
- Radius-constrained connectivity

**Example:**
```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(10.0, 0.0))
val graph = vec2MaximumDiameterGraph(points, maxDiameter = 2.0)
// Only connects first two points (distance = 1.0 < 2.0)
```

### 8. Nearest Neighbor Graph (1-NN)
```kotlin
fun vec2NearestNeighborGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3NearestNeighborGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Each point connects to its single nearest neighbor. Equivalent to KNN with k=1.

**Use Cases:**
- Clustering initialization
- Chain detection
- Sparse connectivity

### 9. Urquhart Graph
```kotlin
fun vec2UrquhartGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3UrquhartGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Subgraph of Delaunay triangulation. Currently approximated using MST.

**Use Cases:**
- Shape skeleton extraction
- Perceptual grouping
- Structure analysis

### 10. Delaunay-Inspired Graph
```kotlin
fun vec2DelaunayGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Simplified Delaunay-like graph using Gabriel graph properties.

**Use Cases:**
- Mesh generation
- Surface reconstruction
- Spatial interpolation

**Note:** For full Delaunay triangulation, consider using a dedicated computational geometry library.

### 11. Maximum Cut Graph
```kotlin
fun vec2MaximumCutGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3MaximumCutGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Approximates maximum cut by partitioning points and maximizing cross-partition edges.

**Use Cases:**
- Graph partitioning
- Bipartite-like structures
- Load balancing

**Algorithm:** Greedy partitioning by coordinate ordering.

### 12. Complete Euclidean Graph
```kotlin
fun vec2CompleteGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec3CompleteGraph(points: List<Vec3>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```
Connects every pair of points.

**Use Cases:**
- All-pairs distance matrix
- Dense connectivity analysis
- Baseline for graph comparisons

**Properties:**
- `n(n-1)/2` edges for `n` points
- Maximum connectivity

## Usage Examples

### Basic KNN Graph
```kotlin
import afield.stdlib.*

val points = listOf(
    vec(0.0, 0.0),
    vec(1.0, 0.0),
    vec(0.5, 0.5),
    vec(1.0, 1.0)
)

val knnGraph = vec2KnnGraph(points, k = 2)

println("Point count: ${knnGraph.pointCount}")
println("Edge count: ${knnGraph.edges.size}")
println("Degree of point 0: ${knnGraph.degree(0)}")
println("Neighbors of point 0: ${knnGraph.neighbors(0)}")
```

### Minimum Spanning Tree
```kotlin
val points = List(20) { 
    vec(Random.nextDouble(-10.0, 10.0), Random.nextDouble(-10.0, 10.0))
}

val mst = vec2MinimumSpanningTree(points)

println("MST edges: ${mst.edges.size}") // Should be 19 (n-1)
val totalWeight = mst.edges.sumOf { it.weight }
println("Total MST weight: $totalWeight")
```

### Gabriel Graph for Shape Analysis
```kotlin
val shapePoints = listOf(
    vec(0.0, 0.0), vec(1.0, 0.0),
    vec(1.0, 1.0), vec(0.0, 1.0)
)

val gabriel = vec2GabrielGraph(shapePoints)

// Analyze connectivity
for (i in 0 until gabriel.pointCount) {
    println("Point $i connects to: ${gabriel.neighbors(i)}")
}
```

### 3D Point Cloud Connectivity
```kotlin
val cloud3d = List(100) {
    vec(
        Random.nextDouble(-5.0, 5.0),
        Random.nextDouble(-5.0, 5.0),
        Random.nextDouble(-5.0, 5.0)
    )
}

val knn3d = vec3KnnGraph(cloud3d, k = 6)
val rng3d = vec3RelativeNeighborhoodGraph(cloud3d)

println("KNN edges: ${knn3d.edges.size}")
println("RNG edges: ${rng3d.edges.size}")
```

### Comparing Graph Sparsity
```kotlin
val points = List(50) { vec(Random.nextDouble(), Random.nextDouble()) }

val complete = vec2CompleteGraph(points)
val gabriel = vec2GabrielGraph(points)
val rng = vec2RelativeNeighborhoodGraph(points)
val mst = vec2MinimumSpanningTree(points)

println("Complete: ${complete.edges.size} edges")
println("Gabriel:  ${gabriel.edges.size} edges")
println("RNG:      ${rng.edges.size} edges")
println("MST:      ${mst.edges.size} edges")
```

## Options

```kotlin
data class GraphConnectivityOptions(
    val allowDuplicatePoints: Boolean = true
)
```

Currently supports:
- `allowDuplicatePoints`: Whether to allow duplicate points in the input (default: true)

## Algorithm Properties

### Hierarchy of Graphs (by inclusion)
```
Complete Graph
    ⊃ Delaunay Triangulation
        ⊃ Gabriel Graph
            ⊃ Relative Neighborhood Graph
                ⊃ Minimum Spanning Tree
```

### Time Complexity

| Algorithm | 2D Time | 3D Time | Space |
|-----------|---------|---------|-------|
| KNN (naive) | O(kn²) | O(kn²) | O(kn) |
| MST | O(n² log n) | O(n² log n) | O(n²) |
| RNG | O(n³) | O(n³) | O(n²) |
| Gabriel | O(n³) | O(n³) | O(n²) |
| Complete | O(n²) | O(n²) | O(n²) |

**Note:** For large point sets (n > 1000), consider using the existing `vec2NN` and `vec3NN` indexes from `smile_neighbors.kt` for efficient nearest neighbor queries, then build graphs on top.

## Testing

Run the comprehensive smoke tests:
```kotlin
import afield.examples.stdlib.GraphConnectivitySmokeTest

fun main() {
    GraphConnectivitySmokeTest.runTests()
}
```

The test suite includes:
- 24 test functions covering all algorithms
- 2D and 3D variants
- Edge case handling
- Graph structure validation
- Symmetry checks

## Performance Tips

1. **For large point sets (n > 1000)**: Use KNN with small k values or RNG/Gabriel which are typically sparse
2. **For dense connectivity**: Use Maximum Diameter Graph with appropriate radius
3. **For hierarchical analysis**: Start with MST, then progressively add edges
4. **For efficiency**: Reuse existing `vec2NN`/`vec3NN` indexes for multiple graph constructions

## Future Enhancements

Possible additions:
- Voronoi diagrams
- Full Delaunay triangulation (using JTS or similar)
- Approximate algorithms for large datasets
- Incremental graph updates
- Graph serialization

## References

- **Relative Neighborhood Graph**: Toussaint, G. T. (1980)
- **Gabriel Graph**: Gabriel, K. R., & Sokal, R. R. (1969)
- **Beta-Skeleton**: Kirkpatrick, D. G., & Radke, J. D. (1985)
- **Urquhart Graph**: Urquhart, R. (1982)

## License

Part of the Field4 project.

## JTS Integration Features

### Voronoi Diagrams

```kotlin
fun vec2VoronoiDiagram(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): VoronoiDiagram
```

Computes the Voronoi diagram using JTS. The Voronoi diagram divides the plane into regions where each region contains all points closer to one site than to any other.

**Data Structures:**
- `VoronoiDiagram`: Contains cells for each site point
- `VoronoiCell`: Represents a single Voronoi cell with vertices and site index

**Example:**
```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(0.5, 1.0))
val voronoi = vec2VoronoiDiagram(points)

for (cell in voronoi.cells) {
    println("Site ${cell.siteIndex} has ${cell.vertices.size} vertices")
}
```

### Full Delaunay Triangulation

```kotlin
fun vec2DelaunayGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
fun vec2DelaunayTriangles(points: List<Vec2>): List<Triple<Int, Int, Int>>
```

Full Delaunay triangulation using JTS, not just an approximation. The triangulation ensures no point is inside the circumcircle of any triangle.

**`vec2DelaunayTriangles`** returns actual triangles as triples of point indices, useful for mesh generation and rendering.

**Example:**
```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(0.5, 1.0))
val delaunay = vec2DelaunayGraph(points)
val triangles = vec2DelaunayTriangles(points)

for ((i, j, k) in triangles) {
    println("Triangle: [$i, $j, $k]")
}
```

### Convex Hull

```kotlin
fun vec2ConvexHull(points: List<Vec2>): ConvexHull2D
fun vec2ConvexHullGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```

Computes the convex hull (smallest convex polygon containing all points) using JTS.

**Data Structure:**
- `ConvexHull2D`: Contains hull vertices and their indices in the original point list

**Example:**
```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(1.0, 1.0), vec(0.0, 1.0), vec(0.5, 0.5))
val hull = vec2ConvexHull(points)

println("Hull has ${hull.hullPoints.size} vertices")
println("Hull point indices: ${hull.hullIndices}")
```

### Hull Projection Graph

```kotlin
fun vec2HullProjectionGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```

Connects each interior point to its nearest point on the convex hull. Useful for boundary-interior relationships.

### Voronoi Dual Graph

```kotlin
fun vec2VoronoiDualGraph(points: List<Vec2>, options: GraphConnectivityOptions = GraphConnectivityOptions()): PointGraph
```

Computes the dual graph of the Voronoi diagram, which is equivalent to the Delaunay triangulation. Two sites are connected if their Voronoi cells share an edge.

## Custom Distance Metrics

All graph algorithms now support custom distance metrics through the `GraphConnectivityOptions` parameter.

### Options Configuration

```kotlin
data class GraphConnectivityOptions(
    val allowDuplicatePoints: Boolean = true,
    val customDistanceMetric: ((Int, Int, List<*>) -> Double)? = null
)
```

The `customDistanceMetric` parameter accepts a function that takes:
- Two point indices (i and j)
- The list of points
- Returns the distance between the points

### Example: Manhattan Distance

```kotlin
val points = listOf(vec(0.0, 0.0), vec(1.0, 0.0), vec(0.0, 1.0))

// Define Manhattan distance metric
val manhattanMetric: (Int, Int, List<*>) -> Double = { i, j, pts ->
    val p = pts as List<Vec2>
    abs(p[i].x - p[j].x).toDouble() + abs(p[i].y - p[j].y).toDouble()
}

val options = GraphConnectivityOptions(customDistanceMetric = manhattanMetric)
val knn = vec2KnnGraph(points, k = 2, options)

// KNN graph now uses Manhattan distance instead of Euclidean
```

### Example: Weighted Distance

```kotlin
// Distance metric that weights x and y differently
val weightedMetric: (Int, Int, List<*>) -> Double = { i, j, pts ->
    val p = pts as List<Vec2>
    val dx = (p[i].x - p[j].x).toDouble()
    val dy = (p[i].y - p[j].y).toDouble()
    sqrt(2.0 * dx * dx + dy * dy)  // Weight x-axis more heavily
}

val options = GraphConnectivityOptions(customDistanceMetric = weightedMetric)
val mst = vec2MinimumSpanningTree(points, options)
```

### Example: Index-Based Distance

```kotlin
// Distance that depends on point indices (e.g., for graph-structured data)
val indexAwareMetric: (Int, Int, List<*>) -> Double = { i, j, pts ->
    val p = pts as List<Vec2>
    val euclidean = sqrt(
        (p[i].x - p[j].x).toDouble().pow(2) + 
        (p[i].y - p[j].y).toDouble().pow(2)
    )
    // Penalize connections between distant indices
    euclidean * (1.0 + 0.1 * abs(i - j))
}

val options = GraphConnectivityOptions(customDistanceMetric = indexAwareMetric)
val gabriel = vec2GabrielGraph(points, options)
```

### Applications of Custom Metrics

1. **Non-Euclidean Spaces**: Manhattan, Chebyshev, or other Lp norms
2. **Anisotropic Distances**: Different weights for different dimensions
3. **Graph-Structured Data**: Incorporate graph distances or connectivity
4. **Feature Spaces**: Domain-specific distance functions (e.g., color spaces, semantic similarity)
5. **Constrained Spaces**: Geodesic distances on surfaces or manifolds

