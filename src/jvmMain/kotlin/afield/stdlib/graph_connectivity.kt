/**
 * Self-contained graph / computational-geometry library over 2D and 3D point sets
 * (`Vec2`/`Vec3`). Every algorithm has a `vec2*` and (where meaningful) a `vec3*` entry point
 * returning a [PointGraph]; see each function's own KDoc for its definition and complexity.
 *
 * Proximity / neighbourhood families: KNN ([vec2KnnGraph]), Orthant ([vec2OrthantGraph]),
 * Relative Neighborhood ([vec2RelativeNeighborhoodGraph]), Gabriel ([vec2GabrielGraph]),
 * Beta-Skeleton ([vec2BetaSkeleton]), Nearest-Neighbor ([vec2NearestNeighborGraph]),
 * Minimum Spanning Tree ([vec2MinimumSpanningTree]), Maximum-Diameter/radius
 * ([vec2MaximumDiameterGraph]), Maximum-Cut ([vec2MaximumCutGraph]) and Complete
 * ([vec2CompleteGraph]) graphs.
 *
 * JTS-backed (2D only): full Delaunay triangulation ([vec2DelaunayGraph], [vec2DelaunayTriangles]),
 * Urquhart ([vec2UrquhartGraph], a real Urquhart graph built by removing the longest edge of each
 * Delaunay triangle — the 3D [vec3UrquhartGraph] is an MST approximation because Field only wires
 * 2D Delaunay), Voronoi diagram/dual ([vec2VoronoiDiagram], [vec2VoronoiDualGraph]) and convex
 * hull ([vec2ConvexHull], [vec2ConvexHullGraph], [vec2HullProjectionGraph]).
 *
 * Traversal / analysis: BFS/DFS/A* path search, [GraphEdgeVisitState] greedy walks and
 * graph-diameter path decomposition (see the search/path functions below), plus FLine rendering
 * integration in `fline_graph_connectivity.kt`.
 *
 * Custom distance metrics: pass [GraphConnectivityOptions.customDistanceMetric]. It is honoured by
 * the distance-driven algorithms (KNN, MST, RNG, Gabriel, Beta-Skeleton, Delaunay/Urquhart edge
 * weights, etc.). This includes [vec2OrthantGraph]/[vec3OrthantGraph], whose nearest-neighbour
 * ranking within each orthant honours `customDistanceMetric` when supplied (Euclidean otherwise).
 *
 * Smoke tests: `afield.examples.stdlib.GraphConnectivitySmokeTest` (its `runTests()` drives 35
 * checks, covering path search, edge-visit-state and FLine rendering as well as the core graphs).
 * Live rendered examples: `public/docs/graph_connectivity_examples.md`.
 */
@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder
import java.util.PriorityQueue
import kotlin.collections.ArrayDeque
import kotlin.collections.set
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Represents an edge between two points in a graph.
 *
 * @param i Index of the first point
 * @param j Index of the second point
 * @param weight Weight/distance of the edge
 */
data class GraphEdge(val i: Int, val j: Int, val weight: Double) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphEdge) return false
        return (i == other.i && j == other.j) || (i == other.j && j == other.i)
    }

    override fun hashCode(): Int {
        val min = min(i, j)
        val max = max(i, j)
        return 31 * min + max
    }
}

/**
 * Represents a graph computed from a set of points.
 *
 * @param edges List of edges in the graph
 * @param pointCount Number of points in the graph
 */
data class PointGraph(val edges: List<GraphEdge>, val pointCount: Int) {
    /**
     * Get all neighbors of a point.
     */
    fun neighbors(pointIndex: Int): List<Int> {
        return edges.filter { it.i == pointIndex || it.j == pointIndex }
            .map { if (it.i == pointIndex) it.j else it.i }
    }

    fun neighbors1(pointIndex: Int): List<Int> {
        return edges.filter { it.i == pointIndex  }
            .map { it.j }
    }

    /**
     * Get the degree (number of neighbors) of a point.
     */
    fun degree(pointIndex: Int): Int = neighbors(pointIndex).size
}

/**
 * Options for graph connectivity algorithms.
 *
 * @param allowDuplicatePoints Whether to allow duplicate points
 * @param customDistanceMetric Custom distance function taking two point indices and the point list.
 *                             If null, uses standard Euclidean distance.
 */
data class GraphConnectivityOptions(
    val allowDuplicatePoints: Boolean = true,
    val customDistanceMetric: ((Int, Int, List<*>) -> Double)? = null
)

/**
 * Represents a path through a [PointGraph].
 *
 * @param nodes Ordered sequence of node indices in the path
 * @param edges Ordered sequence of edges traversed by the path
 * @param totalWeight Sum of traversed edge weights
 */
data class GraphPath(
    val nodes: List<Int>,
    val edges: List<GraphEdge>,
    val totalWeight: Double
) {
    init {
        require(nodes.isNotEmpty()) { "GraphPath.nodes must not be empty" }
        require(nodes.size == edges.size + 1) {
            "GraphPath requires nodes.size == edges.size + 1, found ${nodes.size} and ${edges.size}"
        }
    }

    val start: Int get() = nodes.first()
    val end: Int get() = nodes.last()
}

/**
 * Tracks visited and unvisited edges for path cover generation.
 *
 * Edges are always treated as undirected according to [GraphEdge.equals].
 */
data class GraphEdgeVisitState(
    val pointCount: Int,
    val visitedEdges: Set<GraphEdge>,
    val unvisitedEdges: Set<GraphEdge>
) {
    init {
        require(pointCount >= 0) { "GraphEdgeVisitState.pointCount must be >= 0, found $pointCount" }
        require((visitedEdges intersect unvisitedEdges).isEmpty()) {
            "GraphEdgeVisitState: visited and unvisited sets must be disjoint"
        }

        val allEdges = visitedEdges + unvisitedEdges
        allEdges.forEachIndexed { idx, edge ->
            require(edge.i in 0 until pointCount) {
                "GraphEdgeVisitState: edge[$idx].i=${edge.i} is out of range for pointCount=$pointCount"
            }
            require(edge.j in 0 until pointCount) {
                "GraphEdgeVisitState: edge[$idx].j=${edge.j} is out of range for pointCount=$pointCount"
            }
            require(edge.weight.isFinite()) {
                "GraphEdgeVisitState: edge[$idx] weight must be finite, found ${edge.weight}"
            }
        }
    }

    val allEdges: Set<GraphEdge>
        get() = visitedEdges + unvisitedEdges

    fun isVisited(edge: GraphEdge): Boolean = edge in visitedEdges

    fun isUnvisited(edge: GraphEdge): Boolean = edge in unvisitedEdges

    fun markVisited(edges: Iterable<GraphEdge>): GraphEdgeVisitState {
        val moved = edges.filter { it in unvisitedEdges }.toSet()
        if (moved.isEmpty()) return this
        return copy(
            visitedEdges = visitedEdges + moved,
            unvisitedEdges = unvisitedEdges - moved
        )
    }

    fun markVisited(edge: GraphEdge): GraphEdgeVisitState = markVisited(listOf(edge))

    fun markUnvisited(edges: Iterable<GraphEdge>): GraphEdgeVisitState {
        val moved = edges.filter { it in visitedEdges }.toSet()
        if (moved.isEmpty()) return this
        return copy(
            visitedEdges = visitedEdges - moved,
            unvisitedEdges = unvisitedEdges + moved
        )
    }

    fun markUnvisited(edge: GraphEdge): GraphEdgeVisitState = markUnvisited(listOf(edge))

    fun visitedGraph(): PointGraph = PointGraph(visitedEdges.toList(), pointCount)

    fun unvisitedGraph(): PointGraph = PointGraph(unvisitedEdges.toList(), pointCount)
}

/**
 * Result containing a list of paths and the updated edge-visit state.
 */
data class GraphPathSetResult(
    val paths: List<GraphPath>,
    val edgeState: GraphEdgeVisitState
)

/**
 * Build a [GraphEdgeVisitState] from a [PointGraph].
 */
fun pointGraphEdgeVisitState(
    graph: PointGraph,
    initiallyVisited: Collection<GraphEdge> = emptyList()
): GraphEdgeVisitState {
    validatePointGraph(graph, "pointGraphEdgeVisitState")
    val allEdges = graph.edges.toSet()
    val visited = initiallyVisited.filter { it in allEdges }.toSet()
    return GraphEdgeVisitState(
        pointCount = graph.pointCount,
        visitedEdges = visited,
        unvisitedEdges = allEdges - visited
    )
}

/**
 * Convenience extension for [pointGraphEdgeVisitState].
 */
fun PointGraph.edgeVisitState(
    initiallyVisited: Collection<GraphEdge> = emptyList()
): GraphEdgeVisitState = pointGraphEdgeVisitState(this, initiallyVisited)

/**
 * Find an unweighted shortest path from [start] to [goal] using breadth-first search.
 *
 * @param graph Graph to traverse
 * @param start Start node index
 * @param goal Goal node index
 * @param allowedEdges Optional edge subset constraint
 * @return Path if reachable, else null
 */
fun graphBfsPath(
    graph: PointGraph,
    start: Int,
    goal: Int,
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    validatePointGraph(graph, "graphBfsPath")
    validatePointIndex(start, graph.pointCount, "graphBfsPath", "start")
    validatePointIndex(goal, graph.pointCount, "graphBfsPath", "goal")

    if (start == goal) return GraphPath(listOf(start), emptyList(), 0.0)

    val filteredEdges = filterGraphEdges(graph, allowedEdges)
    val adjacency = buildAdjacency(graph.pointCount, filteredEdges)
    val previous = IntArray(graph.pointCount) { -1 }
    val discovered = BooleanArray(graph.pointCount)
    val queue = ArrayDeque<Int>()

    discovered[start] = true
    queue.addLast(start)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current == goal) break

        for (entry in adjacency[current]) {
            if (discovered[entry.to]) continue
            discovered[entry.to] = true
            previous[entry.to] = current
            queue.addLast(entry.to)
        }
    }

    if (!discovered[goal]) return null
    val nodes = reconstructPathNodes(previous, start, goal) ?: return null
    return pathFromNodes(nodes, buildEdgeLookup(filteredEdges))
}

/**
 * Find a depth-first path from [start] to [goal].
 *
 * @param graph Graph to traverse
 * @param start Start node index
 * @param goal Goal node index
 * @param allowedEdges Optional edge subset constraint
 * @return Path if reachable, else null
 */
fun graphDfsPath(
    graph: PointGraph,
    start: Int,
    goal: Int,
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    validatePointGraph(graph, "graphDfsPath")
    validatePointIndex(start, graph.pointCount, "graphDfsPath", "start")
    validatePointIndex(goal, graph.pointCount, "graphDfsPath", "goal")

    if (start == goal) return GraphPath(listOf(start), emptyList(), 0.0)

    val filteredEdges = filterGraphEdges(graph, allowedEdges)
    val adjacency = buildAdjacency(graph.pointCount, filteredEdges)
    val previous = IntArray(graph.pointCount) { -1 }
    val discovered = BooleanArray(graph.pointCount)
    val stack = ArrayDeque<Int>()

    discovered[start] = true
    stack.addLast(start)

    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (current == goal) break

        for (idx in adjacency[current].lastIndex downTo 0) {
            val entry = adjacency[current][idx]
            if (discovered[entry.to]) continue
            discovered[entry.to] = true
            previous[entry.to] = current
            stack.addLast(entry.to)
        }
    }

    if (!discovered[goal]) return null
    val nodes = reconstructPathNodes(previous, start, goal) ?: return null
    return pathFromNodes(nodes, buildEdgeLookup(filteredEdges))
}

/**
 * Find a weighted path from [start] to [goal] using A*.
 *
 * @param graph Graph to traverse
 * @param start Start node index
 * @param goal Goal node index
 * @param heuristic Heuristic estimate from current node to goal
 * @param allowedEdges Optional edge subset constraint
 * @return Path if reachable, else null
 */
fun graphAStarPath(
    graph: PointGraph,
    start: Int,
    goal: Int,
    heuristic: (Int, Int) -> Double = { _, _ -> 0.0 },
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    validatePointGraph(graph, "graphAStarPath")
    validatePointIndex(start, graph.pointCount, "graphAStarPath", "start")
    validatePointIndex(goal, graph.pointCount, "graphAStarPath", "goal")

    if (start == goal) return GraphPath(listOf(start), emptyList(), 0.0)

    val filteredEdges = filterGraphEdges(graph, allowedEdges)
    requireNonNegativeEdgeWeights(filteredEdges, "graphAStarPath")

    val adjacency = buildAdjacency(graph.pointCount, filteredEdges)
    val edgeLookup = buildEdgeLookup(filteredEdges)
    val gScore = DoubleArray(graph.pointCount) { Double.POSITIVE_INFINITY }
    val fScore = DoubleArray(graph.pointCount) { Double.POSITIVE_INFINITY }
    val previous = IntArray(graph.pointCount) { -1 }
    val queue = PriorityQueue<PriorityNode>(compareBy<PriorityNode> { it.priority }.thenBy { it.node })

    gScore[start] = 0.0
    fScore[start] = heuristic(start, goal)
    queue.add(PriorityNode(start, fScore[start]))

    while (queue.isNotEmpty()) {
        val current = queue.poll()
        if (current.priority > fScore[current.node] + GRAPH_SEARCH_EPSILON) continue
        if (current.node == goal) break

        for (entry in adjacency[current.node]) {
            val tentative = gScore[current.node] + entry.edge.weight
            if (tentative + GRAPH_SEARCH_EPSILON < gScore[entry.to]) {
                previous[entry.to] = current.node
                gScore[entry.to] = tentative
                fScore[entry.to] = tentative + heuristic(entry.to, goal)
                queue.add(PriorityNode(entry.to, fScore[entry.to]))
            }
        }
    }

    if (gScore[goal].isInfinite()) return null
    val nodes = reconstructPathNodes(previous, start, goal) ?: return null
    val path = pathFromNodes(nodes, edgeLookup)
    return path.copy(totalWeight = gScore[goal])
}

/**
 * A* path helper using Euclidean distance on 2D points as the heuristic.
 */
fun vec2AStarPath(
    graph: PointGraph,
    points: List<Vec2>,
    start: Int,
    goal: Int,
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    require(points.size >= graph.pointCount) {
        "vec2AStarPath: points must contain at least graph.pointCount values (${graph.pointCount}), found ${points.size}"
    }
    validatePoints2D(points.take(graph.pointCount), "vec2AStarPath")

    return graphAStarPath(
        graph = graph,
        start = start,
        goal = goal,
        heuristic = { a, b -> distance2D(points[a], points[b]) },
        allowedEdges = allowedEdges
    )
}

/**
 * A* path helper using Euclidean distance on 3D points as the heuristic.
 */
fun vec3AStarPath(
    graph: PointGraph,
    points: List<Vec3>,
    start: Int,
    goal: Int,
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    require(points.size >= graph.pointCount) {
        "vec3AStarPath: points must contain at least graph.pointCount values (${graph.pointCount}), found ${points.size}"
    }
    validatePoints3D(points.take(graph.pointCount), "vec3AStarPath")

    return graphAStarPath(
        graph = graph,
        start = start,
        goal = goal,
        heuristic = { a, b -> distance3D(points[a], points[b]) },
        allowedEdges = allowedEdges
    )
}

/**
 * Build a set of paths from [anchor] by repeatedly choosing the closest reachable point
 * that is incident to at least one unvisited edge.
 *
 * Paths are computed on the current unvisited subgraph, so each selected path always
 * contributes newly visited edges.
 */
fun graphGreedyClosestUnvisitedPaths(
    graph: PointGraph,
    anchor: Int,
    edgeState: GraphEdgeVisitState = graph.edgeVisitState()
): GraphPathSetResult {
    validatePointGraph(graph, "graphGreedyClosestUnvisitedPaths")
    validatePointIndex(anchor, graph.pointCount, "graphGreedyClosestUnvisitedPaths", "anchor")
    var normalizedState = normalizeEdgeVisitState(graph, edgeState, "graphGreedyClosestUnvisitedPaths")

    val paths = mutableListOf<GraphPath>()

    while (normalizedState.unvisitedEdges.isNotEmpty()) {
        requireNonNegativeEdgeWeights(normalizedState.unvisitedEdges, "graphGreedyClosestUnvisitedPaths")

        val adjacency = buildAdjacency(graph.pointCount, normalizedState.unvisitedEdges)
        val dijkstra = runDijkstra(adjacency, anchor)
        val edgeLookup = buildEdgeLookup(normalizedState.unvisitedEdges)
        val candidates = normalizedState.unvisitedEdges.asSequence()
            .flatMap { sequenceOf(it.i, it.j) }
            .toSet()

        var bestTarget = -1
        var bestDistance = Double.POSITIVE_INFINITY

        for (candidate in candidates) {
            if (candidate == anchor) continue
            val distance = dijkstra.distances[candidate]
            if (distance.isInfinite()) continue

            val isBetterDistance = distance < bestDistance - GRAPH_SEARCH_EPSILON
            val isTieBreak = abs(distance - bestDistance) <= GRAPH_SEARCH_EPSILON && (bestTarget < 0 || candidate < bestTarget)
            if (bestTarget < 0 || isBetterDistance || isTieBreak) {
                bestTarget = candidate
                bestDistance = distance
            }
        }

        if (bestTarget < 0) break

        val nodes = reconstructPathNodes(dijkstra.previous, anchor, bestTarget) ?: break
        val path = pathFromNodes(nodes, edgeLookup)
        if (path.edges.isEmpty()) break

        paths += path
        normalizedState = normalizedState.markVisited(path.edges)
    }

    return GraphPathSetResult(paths, normalizedState)
}

/**
 * Compute a weighted graph diameter path (the shortest path between the farthest pair of nodes).
 *
 * This is the "longest shortest path" over the provided graph/edge subset.
 */
fun graphDiameterPath(
    graph: PointGraph,
    allowedEdges: Set<GraphEdge>? = null
): GraphPath? {
    validatePointGraph(graph, "graphDiameterPath")
    val filteredEdges = filterGraphEdges(graph, allowedEdges)
    if (filteredEdges.isEmpty()) return null
    requireNonNegativeEdgeWeights(filteredEdges, "graphDiameterPath")

    val adjacency = buildAdjacency(graph.pointCount, filteredEdges)
    val edgeLookup = buildEdgeLookup(filteredEdges)
    val activeNodes = adjacency.indices.filter { adjacency[it].isNotEmpty() }
    if (activeNodes.isEmpty()) return null

    var bestDistance = Double.NEGATIVE_INFINITY
    var bestPath: GraphPath? = null

    for (source in activeNodes) {
        val dijkstra = runDijkstra(adjacency, source)
        for (target in activeNodes) {
            if (target == source) continue
            val distance = dijkstra.distances[target]
            if (distance.isInfinite()) continue
            if (distance <= bestDistance + GRAPH_SEARCH_EPSILON) continue

            val nodes = reconstructPathNodes(dijkstra.previous, source, target) ?: continue
            val path = pathFromNodes(nodes, edgeLookup)
            if (path.edges.isEmpty()) continue

            bestDistance = distance
            bestPath = path.copy(totalWeight = distance)
        }
    }

    return bestPath ?: edgeToPath(filteredEdges.first())
}

/**
 * Iteratively removes diameter paths from the unvisited edge set until all reachable
 * edges are consumed, producing a long-to-short path decomposition.
 */
fun graphDiameterPathDecomposition(
    graph: PointGraph,
    edgeState: GraphEdgeVisitState = graph.edgeVisitState()
): GraphPathSetResult {
    validatePointGraph(graph, "graphDiameterPathDecomposition")
    var normalizedState = normalizeEdgeVisitState(graph, edgeState, "graphDiameterPathDecomposition")
    val paths = mutableListOf<GraphPath>()

    while (normalizedState.unvisitedEdges.isNotEmpty()) {
        val path = graphDiameterPath(graph, normalizedState.unvisitedEdges)
            ?: edgeToPath(normalizedState.unvisitedEdges.first())
        if (path.edges.isEmpty()) break

        paths += path
        normalizedState = normalizedState.markVisited(path.edges)
    }

    return GraphPathSetResult(paths, normalizedState)
}

/**
 * Build an anchored cover by combining:
 * 1) Greedy closest-unvisited anchored paths
 * 2) Diameter decomposition for any disconnected leftovers
 */
fun graphAnchoredGreedyThenDiameterCover(
    graph: PointGraph,
    anchor: Int,
    edgeState: GraphEdgeVisitState = graph.edgeVisitState()
): GraphPathSetResult {
    val greedy = graphGreedyClosestUnvisitedPaths(graph, anchor, edgeState)
    if (greedy.edgeState.unvisitedEdges.isEmpty()) return greedy

    val remainder = graphDiameterPathDecomposition(graph, greedy.edgeState)
    return GraphPathSetResult(
        paths = greedy.paths + remainder.paths,
        edgeState = remainder.edgeState
    )
}

// ==================== 2D Graph Algorithms ====================

/**
 * Compute a K-Nearest Neighbors graph for 2D points.
 *
 * Each point is connected to its k nearest neighbors.
 *
 * @param points List of 2D points
 * @param k Number of nearest neighbors per point
 * @param options Graph connectivity options
 * @return PointGraph with KNN edges
 */
fun vec2KnnGraph(
    points: List<Vec2>,
    k: Int,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2KnnGraph: points must not be empty" }
    require(k > 0) { "vec2KnnGraph: k must be > 0, found $k" }
    validatePoints2D(points, "vec2KnnGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        val distances = points.indices
            .filter { it != i }
            .map { j -> j to computeDistance2D(points[i], points[j], options, i, j, points) }
            .sortedBy { it.second }
            .take(minOf(k, points.size - 1))

        for ((j, dist) in distances) {
            edges.add(GraphEdge(i, j, dist))
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a K-Nearest Neighbors graph for 3D points.
 *
 * Each point is connected to its k nearest neighbors.
 *
 * @param points List of 3D points
 * @param k Number of nearest neighbors per point
 * @param options Graph connectivity options
 * @return PointGraph with KNN edges
 */
fun vec3KnnGraph(
    points: List<Vec3>,
    k: Int,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3KnnGraph: points must not be empty" }
    require(k > 0) { "vec3KnnGraph: k must be > 0, found $k" }
    validatePoints3D(points, "vec3KnnGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        val distances = points.indices
            .filter { it != i }
            .map { j -> j to computeDistance3D(points[i], points[j], options, i, j, points) }
            .sortedBy { it.second }
            .take(minOf(k, points.size - 1))

        for ((j, dist) in distances) {
            edges.add(GraphEdge(i, j, dist))
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute an Orthant Neighborhood Graph for 2D points.
 *
 * Two points are connected if they are mutual nearest neighbors in the same orthant.
 * An orthant is a quadrant defined by the coordinate axes relative to a point.
 *
 * Note: orthant membership is axis-based; the nearest-neighbour distance ranking within each orthant
 * uses [GraphConnectivityOptions.customDistanceMetric] when provided (Euclidean otherwise).
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with orthant neighborhood edges
 */
fun vec2OrthantGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2OrthantGraph: points must not be empty" }
    validatePoints2D(points, "vec2OrthantGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        val pi = points[i]

        // Four orthants: (+,+), (+,-), (-,+), (-,-)
        val orthants = listOf(
            listOf(true, true),   // NE: x>0, y>0
            listOf(true, false),  // SE: x>0, y<0
            listOf(false, true),  // NW: x<0, y>0
            listOf(false, false)  // SW: x<0, y<0
        )

        for (orthant in orthants) {
            val (xPos, yPos) = orthant

            // Find nearest neighbor in this orthant
            var nearest: Int? = null
            var nearestDist = Double.POSITIVE_INFINITY

            for (j in points.indices) {
                if (i == j) continue
                val pj = points[j]
                val dx = pj.x - pi.x
                val dy = pj.y - pi.y

                // Check if point is in the correct orthant
                val inOrthant = if (xPos) dx > 0 else dx < 0
                val inOrthantY = if (yPos) dy > 0 else dy < 0

                if (inOrthant && inOrthantY) {
                    val dist = computeDistance2D(pi, pj, options, i, j, points)
                    if (dist < nearestDist) {
                        nearestDist = dist
                        nearest = j
                    }
                }
            }

            if (nearest != null) {
                edges.add(GraphEdge(i, nearest, nearestDist))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute an Orthant Neighborhood Graph for 3D points.
 *
 * Two points are connected if they are mutual nearest neighbors in the same orthant.
 * An orthant is an octant defined by the coordinate axes relative to a point.
 *
 * Note: orthant membership is axis-based; the nearest-neighbour distance ranking within each orthant
 * uses [GraphConnectivityOptions.customDistanceMetric] when provided (Euclidean otherwise).
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with orthant neighborhood edges
 */
fun vec3OrthantGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3OrthantGraph: points must not be empty" }
    validatePoints3D(points, "vec3OrthantGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        val pi = points[i]

        // Eight orthants (octants) in 3D
        val orthants = listOf(
            listOf(true, true, true),     // +,+,+
            listOf(true, true, false),    // +,+,-
            listOf(true, false, true),    // +,-,+
            listOf(true, false, false),   // +,-,-
            listOf(false, true, true),    // -,+,+
            listOf(false, true, false),   // -,+,-
            listOf(false, false, true),   // -,-,+
            listOf(false, false, false)   // -,-,-
        )

        for (orthant in orthants) {
            val (xPos, yPos, zPos) = orthant

            // Find nearest neighbor in this orthant
            var nearest: Int? = null
            var nearestDist = Double.POSITIVE_INFINITY

            for (j in points.indices) {
                if (i == j) continue
                val pj = points[j]
                val dx = pj.x - pi.x
                val dy = pj.y - pi.y
                val dz = pj.z - pi.z

                // Check if point is in the correct orthant
                val inOrthantX = if (xPos) dx > 0 else dx < 0
                val inOrthantY = if (yPos) dy > 0 else dy < 0
                val inOrthantZ = if (zPos) dz > 0 else dz < 0

                if (inOrthantX && inOrthantY && inOrthantZ) {
                    val dist = computeDistance3D(pi, pj, options, i, j, points)
                    if (dist < nearestDist) {
                        nearestDist = dist
                        nearest = j
                    }
                }
            }

            if (nearest != null) {
                edges.add(GraphEdge(i, nearest, nearestDist))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Relative Neighborhood Graph (RNG) for 2D points.
 *
 * An edge (u,v) is in RNG if there is no other point w such that
 * max(d(u,w), d(v,w)) < d(u,v).
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with RNG edges
 */
fun vec2RelativeNeighborhoodGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2RelativeNeighborhoodGraph: points must not be empty" }
    validatePoints2D(points, "vec2RelativeNeighborhoodGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance2D(points[i], points[j], options, i, j, points)
            var isRelativeNeighbor = true

            // Check if any other point violates the RNG condition
            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance2D(points[i], points[k], options, i, k, points)
                val djk = computeDistance2D(points[j], points[k], options, j, k, points)

                if (max(dik, djk) < dij) {
                    isRelativeNeighbor = false
                    break
                }
            }

            if (isRelativeNeighbor) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Relative Neighborhood Graph (RNG) for 3D points.
 *
 * An edge (u,v) is in RNG if there is no other point w such that
 * max(d(u,w), d(v,w)) < d(u,v).
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with RNG edges
 */
fun vec3RelativeNeighborhoodGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3RelativeNeighborhoodGraph: points must not be empty" }
    validatePoints3D(points, "vec3RelativeNeighborhoodGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance3D(points[i], points[j], options, i, j, points)
            var isRelativeNeighbor = true

            // Check if any other point violates the RNG condition
            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance3D(points[i], points[k], options, i, k, points)
                val djk = computeDistance3D(points[j], points[k], options, j, k, points)

                if (max(dik, djk) < dij) {
                    isRelativeNeighbor = false
                    break
                }
            }

            if (isRelativeNeighbor) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Gabriel Graph for 2D points.
 *
 * An edge (u,v) is in the Gabriel graph if the circle with diameter uv contains no other points.
 * This is equivalent to: for all other points w, d(u,w)^2 + d(v,w)^2 >= d(u,v)^2.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with Gabriel graph edges
 */
fun vec2GabrielGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2GabrielGraph: points must not be empty" }
    validatePoints2D(points, "vec2GabrielGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance2D(points[i], points[j], options, i, j, points)
            val dijSq = dij * dij
            var isGabriel = true

            // Check if any other point is inside the circle
            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance2D(points[i], points[k], options, i, k, points)
                val djk = computeDistance2D(points[j], points[k], options, j, k, points)

                // If point is inside the circle with diameter (i,j)
                if (dik * dik + djk * djk < dijSq - 1e-10) {
                    isGabriel = false
                    break
                }
            }

            if (isGabriel) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Gabriel Graph for 3D points.
 *
 * An edge (u,v) is in the Gabriel graph if the sphere with diameter uv contains no other points.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with Gabriel graph edges
 */
fun vec3GabrielGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3GabrielGraph: points must not be empty" }
    validatePoints3D(points, "vec3GabrielGraph")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance3D(points[i], points[j], options, i, j, points)
            val dijSq = dij * dij
            var isGabriel = true

            // Check if any other point is inside the sphere
            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance3D(points[i], points[k], options, i, k, points)
                val djk = computeDistance3D(points[j], points[k], options, j, k, points)

                // If point is inside the sphere with diameter (i,j)
                if (dik * dik + djk * djk < dijSq - 1e-10) {
                    isGabriel = false
                    break
                }
            }

            if (isGabriel) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Beta-Skeleton for 2D points.
 *
 * An edge (u,v) is in the beta-skeleton if the lune (intersection of two circles)
 * defined by parameter beta contains no other points.
 * - beta = 0: nearest neighbor graph
 * - beta = 1: Gabriel graph
 * - beta = 2: Relative neighborhood graph
 *
 * @param points List of 2D points
 * @param beta Parameter controlling the lune shape (must be >= 0)
 * @param options Graph connectivity options
 * @return PointGraph with beta-skeleton edges
 */
fun vec2BetaSkeleton(
    points: List<Vec2>,
    beta: Double = 1.0,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2BetaSkeleton: points must not be empty" }
    require(beta >= 0.0) { "vec2BetaSkeleton: beta must be >= 0, found $beta" }
    validatePoints2D(points, "vec2BetaSkeleton")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance2D(points[i], points[j], options, i, j, points)
            var isBetaSkeleton = true

            // Check if any other point violates the beta-skeleton condition
            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance2D(points[i], points[k], options, i, k, points)
                val djk = computeDistance2D(points[j], points[k], options, j, k, points)

                // Beta-skeleton condition
                when {
                    beta == 0.0 -> {
                        // Nearest neighbor graph - always include if it's the nearest
                        if (dik < dij || djk < dij) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    beta == 1.0 -> {
                        // Gabriel graph condition
                        if (dik * dik + djk * djk < dij * dij - 1e-10) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    beta >= 2.0 -> {
                        // RNG-like condition
                        if (max(dik, djk) < dij * beta / 2.0) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    else -> {
                        // General beta-skeleton condition (simplified)
                        val radius = dij * beta / 2.0
                        val midX = (points[i].x + points[j].x) / 2.0
                        val midY = (points[i].y + points[j].y) / 2.0
                        val dx = points[k].x - midX
                        val dy = points[k].y - midY
                        val distToMid = sqrt(dx * dx + dy * dy)

                        if (distToMid < radius - 1e-10) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                }
            }

            if (isBetaSkeleton) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a Beta-Skeleton for 3D points.
 *
 * @param points List of 3D points
 * @param beta Parameter controlling the skeleton shape (must be >= 0)
 * @param options Graph connectivity options
 * @return PointGraph with beta-skeleton edges
 */
fun vec3BetaSkeleton(
    points: List<Vec3>,
    beta: Double = 1.0,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3BetaSkeleton: points must not be empty" }
    require(beta >= 0.0) { "vec3BetaSkeleton: beta must be >= 0, found $beta" }
    validatePoints3D(points, "vec3BetaSkeleton")

    val edges = mutableSetOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dij = computeDistance3D(points[i], points[j], options, i, j, points)
            var isBetaSkeleton = true

            for (k in points.indices) {
                if (k == i || k == j) continue
                val dik = computeDistance3D(points[i], points[k], options, i, k, points)
                val djk = computeDistance3D(points[j], points[k], options, j, k, points)

                when {
                    beta == 0.0 -> {
                        if (dik < dij || djk < dij) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    beta == 1.0 -> {
                        // Gabriel graph condition
                        if (dik * dik + djk * djk < dij * dij - 1e-10) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    beta >= 2.0 -> {
                        if (max(dik, djk) < dij * beta / 2.0) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                    else -> {
                        val radius = dij * beta / 2.0
                        val midX = (points[i].x + points[j].x) / 2.0
                        val midY = (points[i].y + points[j].y) / 2.0
                        val midZ = (points[i].z + points[j].z) / 2.0
                        val dx = points[k].x - midX
                        val dy = points[k].y - midY
                        val dz = points[k].z - midZ
                        val distToMid = sqrt(dx * dx + dy * dy + dz * dz)

                        if (distToMid < radius - 1e-10) {
                            isBetaSkeleton = false
                            break
                        }
                    }
                }
            }

            if (isBetaSkeleton) {
                edges.add(GraphEdge(i, j, dij))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Compute a KNN-constrained minimum spanning tree for 2D points.
 *
 * Uses Kruskal's algorithm over an undirected K-nearest-neighbor graph.
 * The neighborhood size grows adaptively until a spanning tree exists.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with MST edges
 */
fun vec2MinimumSpanningTree(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2MinimumSpanningTree: points must not be empty" }
    validatePoints2D(points, "vec2MinimumSpanningTree")

    return adaptiveKnnSpanningTree(
        pointCount = points.size,
        knnEdges = { k -> vec2KnnGraph(points, k, options).edges }
    )
}

/**
 * Compute a KNN-constrained minimum spanning tree for 3D points.
 *
 * Uses Kruskal's algorithm over an undirected K-nearest-neighbor graph.
 * The neighborhood size grows adaptively until a spanning tree exists.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with MST edges
 */
fun vec3MinimumSpanningTree(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3MinimumSpanningTree: points must not be empty" }
    validatePoints3D(points, "vec3MinimumSpanningTree")

    return adaptiveKnnSpanningTree(
        pointCount = points.size,
        knnEdges = { k -> vec3KnnGraph(points, k, options).edges }
    )
}

/**
 * Compute a Maximum Diameter Graph for 2D points.
 *
 * Connects all points within a specified maximum distance (diameter).
 *
 * @param points List of 2D points
 * @param maxDiameter Maximum distance for edge creation
 * @param options Graph connectivity options
 * @return PointGraph with edges within maximum diameter
 */
fun vec2MaximumDiameterGraph(
    points: List<Vec2>,
    maxDiameter: Double,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2MaximumDiameterGraph: points must not be empty" }
    require(maxDiameter > 0.0) { "vec2MaximumDiameterGraph: maxDiameter must be > 0, found $maxDiameter" }
    validatePoints2D(points, "vec2MaximumDiameterGraph")

    val edges = mutableListOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dist = computeDistance2D(points[i], points[j], options, i, j, points)
            if (dist <= maxDiameter) {
                edges.add(GraphEdge(i, j, dist))
            }
        }
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute a Maximum Diameter Graph for 3D points.
 *
 * Connects all points within a specified maximum distance (diameter).
 *
 * @param points List of 3D points
 * @param maxDiameter Maximum distance for edge creation
 * @param options Graph connectivity options
 * @return PointGraph with edges within maximum diameter
 */
fun vec3MaximumDiameterGraph(
    points: List<Vec3>,
    maxDiameter: Double,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3MaximumDiameterGraph: points must not be empty" }
    require(maxDiameter > 0.0) { "vec3MaximumDiameterGraph: maxDiameter must be > 0, found $maxDiameter" }
    validatePoints3D(points, "vec3MaximumDiameterGraph")

    val edges = mutableListOf<GraphEdge>()

    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            val dist = computeDistance3D(points[i], points[j], options, i, j, points)
            if (dist <= maxDiameter) {
                edges.add(GraphEdge(i, j, dist))
            }
        }
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute a Nearest Neighbor Graph (1-NN) for 2D points.
 *
 * Each point is connected to its single nearest neighbor.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with nearest neighbor edges
 */
fun vec2NearestNeighborGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    return vec2KnnGraph(points, 1, options)
}

/**
 * Compute a Nearest Neighbor Graph (1-NN) for 3D points.
 *
 * Each point is connected to its single nearest neighbor.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with nearest neighbor edges
 */
fun vec3NearestNeighborGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    return vec3KnnGraph(points, 1, options)
}

/**
 * Compute an Urquhart Graph for 2D points.
 *
 * The Urquhart graph is formed by removing the longest edge from each triangle
 * in the Delaunay triangulation.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with Urquhart graph edges
 */
fun vec2UrquhartGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2UrquhartGraph: points must not be empty" }
    validatePoints2D(points, "vec2UrquhartGraph")

    if (points.size < 3) return vec2DelaunayGraph(points, options)

    val triangles = vec2DelaunayTriangles(points)
    if (triangles.isEmpty()) return vec2DelaunayGraph(points, options)

    val candidateEdges = mutableSetOf<GraphEdge>()
    val removedEdges = mutableSetOf<GraphEdge>()
    val tieTolerance = 1e-9

    for ((a, b, c) in triangles) {
        val ab = GraphEdge(a, b, computeDistance2D(points[a], points[b], options, a, b, points))
        val bc = GraphEdge(b, c, computeDistance2D(points[b], points[c], options, b, c, points))
        val ca = GraphEdge(c, a, computeDistance2D(points[c], points[a], options, c, a, points))
        val triangleEdges = listOf(ab, bc, ca)

        candidateEdges.addAll(triangleEdges)

        val maxWeight = triangleEdges.maxOf { it.weight }
        val longestEdges = triangleEdges.filter { abs(it.weight - maxWeight) <= tieTolerance }

        // When a triangle has a tied longest edge, keep those edges rather than
        // deleting multiple edges due to symmetry or floating-point noise.
        if (longestEdges.size == 1) {
            removedEdges.add(longestEdges.first())
        }
    }

    return PointGraph((candidateEdges - removedEdges).toList(), points.size)
}

/**
 * Compute an Urquhart Graph for 3D points.
 *
 * A full 3D Urquhart graph would require removing longest edges from a
 * 3D Delaunay tetrahedralization. Field currently only has 2D Delaunay
 * support wired in via JTS, so this remains an MST approximation.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with Urquhart graph edges
 */
fun vec3UrquhartGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3UrquhartGraph: points must not be empty" }
    validatePoints3D(points, "vec3UrquhartGraph")

    return vec3MinimumSpanningTree(points, options)
}

/**
 * Compute a full Delaunay triangulation for 2D points using JTS.
 *
 * The Delaunay triangulation is a triangulation where no point is inside
 * the circumcircle of any triangle.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with Delaunay triangulation edges
 */
fun vec2DelaunayGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2DelaunayGraph: points must not be empty" }
    validatePoints2D(points, "vec2DelaunayGraph")

    if (points.size < 2) return PointGraph(emptyList(), points.size)

    // Convert to JTS coordinates
    val coords = points.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }

    // Build Delaunay triangulation
    val builder = DelaunayTriangulationBuilder()
    builder.setSites(coords)
    val triangulation = builder.getEdges(GeometryFactory())

    // Extract edges and map back to point indices
    val edges = mutableSetOf<GraphEdge>()

    for (i in 0 until triangulation.numGeometries) {
        val edge = triangulation.getGeometryN(i)
        val edgeCoords = edge.coordinates

        if (edgeCoords.size >= 2) {
            val c0 = edgeCoords[0]
            val c1 = edgeCoords[1]

            // Find indices of these coordinates in original points
            val idx0 = coords.indexOfFirst { it.equals2D(c0) }
            val idx1 = coords.indexOfFirst { it.equals2D(c1) }

            if (idx0 >= 0 && idx1 >= 0) {
                val dist = computeDistance2D(points[idx0], points[idx1], options, idx0, idx1, points)
                edges.add(GraphEdge(idx0, idx1, dist))
            }
        }
    }

    return PointGraph(edges.toList(), points.size)
}

/**
 * Represents a Voronoi cell in the Voronoi diagram.
 *
 * @param siteIndex Index of the site point that generated this cell
 * @param vertices List of vertex coordinates defining the cell boundary
 */
data class VoronoiCell(
    val siteIndex: Int,
    val vertices: List<Vec2>
)

/**
 * Represents a Voronoi diagram computed from a set of points.
 *
 * @param cells List of Voronoi cells
 * @param pointCount Number of input points
 */
data class VoronoiDiagram(
    val cells: List<VoronoiCell>,
    val pointCount: Int
) {
    /**
     * Get the Voronoi cell for a specific site point.
     */
    fun cellForSite(siteIndex: Int): VoronoiCell? {
        return cells.find { it.siteIndex == siteIndex }
    }
}

/**
 * Compute a Voronoi diagram for 2D points using JTS.
 *
 * The Voronoi diagram divides the plane into regions, where each region
 * contains all points closer to one site than to any other site.
 *
 * @param points List of 2D points (sites)
 * @param options Graph connectivity options
 * @return VoronoiDiagram with cells for each site
 */
fun vec2VoronoiDiagram(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): VoronoiDiagram {
    require(points.isNotEmpty()) { "vec2VoronoiDiagram: points must not be empty" }
    validatePoints2D(points, "vec2VoronoiDiagram")

    if (points.size < 2) {
        return VoronoiDiagram(emptyList(), points.size)
    }

    // Convert to JTS coordinates
    val coords = points.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }

    // Build Voronoi diagram
    val builder = VoronoiDiagramBuilder()
    builder.setSites(coords)
    val voronoi = builder.getDiagram(GeometryFactory())

    // Extract cells
    val cells = mutableListOf<VoronoiCell>()

    for (i in 0 until voronoi.numGeometries) {
        val polygon = voronoi.getGeometryN(i)

        // Find which site this cell corresponds to
        val centroid = polygon.centroid
        var closestSite = 0
        var minDist = Double.POSITIVE_INFINITY

        for (j in coords.indices) {
            val dist = centroid.coordinate.distance(coords[j])
            if (dist < minDist) {
                minDist = dist
                closestSite = j
            }
        }

        // Extract vertices
        val vertices = polygon.coordinates.map {
            vec(it.x.toFloat(), it.y.toFloat())
        }

        cells.add(VoronoiCell(closestSite, vertices))
    }

    return VoronoiDiagram(cells, points.size)
}

/**
 * Compute the dual graph of a Voronoi diagram (equivalent to Delaunay triangulation).
 *
 * Two sites are connected if their Voronoi cells share an edge.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with edges connecting adjacent Voronoi cells
 */
fun vec2VoronoiDualGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    // The dual of Voronoi is the Delaunay triangulation
    return vec2DelaunayGraph(points, options)
}

/**
 * Compute a Maximum Cut approximation graph for 2D points.
 *
 * Creates a bipartite-like graph by partitioning points and maximizing
 * cross-partition edges. Uses a greedy approximation.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with maximum cut edges
 */
fun vec2MaximumCutGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2MaximumCutGraph: points must not be empty" }
    validatePoints2D(points, "vec2MaximumCutGraph")

    if (points.size < 2) return PointGraph(emptyList(), points.size)

    // Simple greedy partitioning based on coordinate
    val partition = BooleanArray(points.size)

    // Partition by alternating sorted order (by x-coordinate)
    val sorted = points.indices.sortedBy { points[it].x }
    sorted.forEachIndexed { index, pointIndex ->
        partition[pointIndex] = index % 2 == 0
    }

    // Add edges between different partitions
    val edges = mutableListOf<GraphEdge>()
    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            if (partition[i] != partition[j]) {
                edges.add(GraphEdge(i, j, computeDistance2D(points[i], points[j], options, i, j, points)))
            }
        }
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute a Maximum Cut approximation graph for 3D points.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with maximum cut edges
 */
fun vec3MaximumCutGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3MaximumCutGraph: points must not be empty" }
    validatePoints3D(points, "vec3MaximumCutGraph")

    if (points.size < 2) return PointGraph(emptyList(), points.size)

    // Partition by alternating sorted order (by x-coordinate)
    val partition = BooleanArray(points.size)
    val sorted = points.indices.sortedBy { points[it].x }
    sorted.forEachIndexed { index, pointIndex ->
        partition[pointIndex] = index % 2 == 0
    }

    // Add edges between different partitions
    val edges = mutableListOf<GraphEdge>()
    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            if (partition[i] != partition[j]) {
                edges.add(GraphEdge(i, j, computeDistance3D(points[i], points[j], options, i, j, points)))
            }
        }
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute a Complete Euclidean Graph for 2D points.
 *
 * Connects every pair of points.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with all possible edges
 */
fun vec2CompleteGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2CompleteGraph: points must not be empty" }
    validatePoints2D(points, "vec2CompleteGraph")

    val edges = mutableListOf<GraphEdge>()
    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            edges.add(GraphEdge(i, j, computeDistance2D(points[i], points[j], options, i, j, points)))
        }
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute a Complete Euclidean Graph for 3D points.
 *
 * Connects every pair of points.
 *
 * @param points List of 3D points
 * @param options Graph connectivity options
 * @return PointGraph with all possible edges
 */
fun vec3CompleteGraph(
    points: List<Vec3>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec3CompleteGraph: points must not be empty" }
    validatePoints3D(points, "vec3CompleteGraph")

    val edges = mutableListOf<GraphEdge>()
    for (i in points.indices) {
        for (j in (i + 1) until points.size) {
            edges.add(GraphEdge(i, j, computeDistance3D(points[i], points[j], options, i, j, points)))
        }
    }

    return PointGraph(edges, points.size)
}

// ==================== Helper Functions ====================

private const val GRAPH_SEARCH_EPSILON = 1e-12

private data class GraphAdjacencyEntry(
    val to: Int,
    val edge: GraphEdge
)

private data class DijkstraResult(
    val distances: DoubleArray,
    val previous: IntArray
)

private data class PriorityNode(
    val node: Int,
    val priority: Double
)

private fun buildAdjacency(
    pointCount: Int,
    edges: Collection<GraphEdge>
): Array<List<GraphAdjacencyEntry>> {
    val adjacency = Array(pointCount) { mutableListOf<GraphAdjacencyEntry>() }
    for (edge in edges) {
        adjacency[edge.i].add(GraphAdjacencyEntry(edge.j, edge))
        adjacency[edge.j].add(GraphAdjacencyEntry(edge.i, edge))
    }
    return Array(pointCount) { index -> adjacency[index].sortedBy { it.to } }
}

private fun filterGraphEdges(graph: PointGraph, allowedEdges: Set<GraphEdge>?): List<GraphEdge> {
    if (allowedEdges == null) return graph.edges
    if (allowedEdges.isEmpty()) return emptyList()
    return graph.edges.filter { it in allowedEdges }
}

private fun buildEdgeLookup(edges: Collection<GraphEdge>): Map<Long, GraphEdge> {
    val lookup = mutableMapOf<Long, GraphEdge>()
    edges.forEach { edge ->
        lookup[edgeKey(edge.i, edge.j)] = edge
    }
    return lookup
}

private fun edgeKey(a: Int, b: Int): Long {
    val lo = min(a, b).toLong() and 0xffffffffL
    val hi = max(a, b).toLong() and 0xffffffffL
    return (lo shl 32) or hi
}

private fun reconstructPathNodes(
    previous: IntArray,
    start: Int,
    goal: Int
): List<Int>? {
    if (start == goal) return listOf(start)
    if (previous[goal] == -1) return null

    val reversePath = mutableListOf<Int>()
    var current = goal

    while (current != -1) {
        reversePath += current
        if (current == start) break
        current = previous[current]
    }

    if (reversePath.lastOrNull() != start) return null
    reversePath.reverse()
    return reversePath
}

private fun pathFromNodes(
    nodes: List<Int>,
    edgeLookup: Map<Long, GraphEdge>
): GraphPath {
    if (nodes.size == 1) return GraphPath(nodes, emptyList(), 0.0)

    val edges = mutableListOf<GraphEdge>()
    var totalWeight = 0.0
    for (i in 0 until nodes.lastIndex) {
        val a = nodes[i]
        val b = nodes[i + 1]
        val edge = requireNotNull(edgeLookup[edgeKey(a, b)]) {
            "Internal graph path error: missing edge between $a and $b"
        }
        edges += edge
        totalWeight += edge.weight
    }
    return GraphPath(nodes, edges, totalWeight)
}

private fun edgeToPath(edge: GraphEdge): GraphPath {
    return GraphPath(
        nodes = listOf(edge.i, edge.j),
        edges = listOf(edge),
        totalWeight = edge.weight
    )
}

private fun runDijkstra(
    adjacency: Array<List<GraphAdjacencyEntry>>,
    source: Int
): DijkstraResult {
    val distance = DoubleArray(adjacency.size) { Double.POSITIVE_INFINITY }
    val previous = IntArray(adjacency.size) { -1 }
    val queue = PriorityQueue<PriorityNode>(compareBy<PriorityNode> { it.priority }.thenBy { it.node })

    distance[source] = 0.0
    queue.add(PriorityNode(source, 0.0))

    while (queue.isNotEmpty()) {
        val current = queue.poll()
        if (current.priority > distance[current.node] + GRAPH_SEARCH_EPSILON) continue

        for (entry in adjacency[current.node]) {
            val tentative = distance[current.node] + entry.edge.weight
            if (tentative + GRAPH_SEARCH_EPSILON < distance[entry.to]) {
                distance[entry.to] = tentative
                previous[entry.to] = current.node
                queue.add(PriorityNode(entry.to, tentative))
            }
        }
    }

    return DijkstraResult(distance, previous)
}

private fun validatePointIndex(
    index: Int,
    pointCount: Int,
    fn: String,
    argument: String
) {
    require(index in 0 until pointCount) {
        "$fn: $argument must be in [0, ${pointCount - 1}], found $index"
    }
}

private fun validatePointGraph(graph: PointGraph, fn: String) {
    require(graph.pointCount >= 0) {
        "$fn: graph.pointCount must be >= 0, found ${graph.pointCount}"
    }

    graph.edges.forEachIndexed { idx, edge ->
        require(edge.i in 0 until graph.pointCount) {
            "$fn: edges[$idx].i=${edge.i} is out of range [0, ${graph.pointCount - 1}]"
        }
        require(edge.j in 0 until graph.pointCount) {
            "$fn: edges[$idx].j=${edge.j} is out of range [0, ${graph.pointCount - 1}]"
        }
        require(edge.weight.isFinite()) {
            "$fn: edges[$idx] weight must be finite, found ${edge.weight}"
        }
    }
}

private fun requireNonNegativeEdgeWeights(edges: Collection<GraphEdge>, fn: String) {
    edges.forEachIndexed { idx, edge ->
        require(edge.weight >= 0.0) {
            "$fn: edge weights must be >= 0, found edges[$idx].weight=${edge.weight}"
        }
    }
}

private fun normalizeEdgeVisitState(
    graph: PointGraph,
    edgeState: GraphEdgeVisitState,
    fn: String
): GraphEdgeVisitState {
    require(edgeState.pointCount == graph.pointCount) {
        "$fn: edgeState.pointCount (${edgeState.pointCount}) must equal graph.pointCount (${graph.pointCount})"
    }

    val allEdges = graph.edges.toSet()
    val visited = edgeState.visitedEdges.filter { it in allEdges }.toSet()
    val explicitlyUnvisited = edgeState.unvisitedEdges.filter { it in allEdges }.toSet() - visited
    val missing = allEdges - visited - explicitlyUnvisited

    return GraphEdgeVisitState(
        pointCount = graph.pointCount,
        visitedEdges = visited,
        unvisitedEdges = explicitlyUnvisited + missing
    )
}

private fun distance2D(a: Vec2, b: Vec2): Double {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    return sqrt(dx * dx + dy * dy)
}

private fun distance3D(a: Vec3, b: Vec3): Double {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    val dz = a.z.toDouble() - b.z.toDouble()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Compute distance between two 2D points, using custom metric if provided.
 */
private fun computeDistance2D(
    a: Vec2,
    b: Vec2,
    options: GraphConnectivityOptions,
    idxA: Int,
    idxB: Int,
    points: List<Vec2>
): Double {
    return options.customDistanceMetric?.invoke(idxA, idxB, points) ?: distance2D(a, b)
}

/**
 * Compute distance between two 3D points, using custom metric if provided.
 */
private fun computeDistance3D(
    a: Vec3,
    b: Vec3,
    options: GraphConnectivityOptions,
    idxA: Int,
    idxB: Int,
    points: List<Vec3>
): Double {
    return options.customDistanceMetric?.invoke(idxA, idxB, points) ?: distance3D(a, b)
}

private fun validatePoints2D(points: List<Vec2>, fn: String) {
    points.forEachIndexed { i, p ->
        require(p.x.isFinite() && p.y.isFinite()) {
            "$fn: points[$i] must be finite, found (${p.x}, ${p.y})"
        }
    }
}

private fun validatePoints3D(points: List<Vec3>, fn: String) {
    points.forEachIndexed { i, p ->
        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) {
            "$fn: points[$i] must be finite, found (${p.x}, ${p.y}, ${p.z})"
        }
    }
}

/**
 * Union-Find (Disjoint Set Union) data structure for Kruskal's algorithm.
 */
private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size) { 0 }

    fun find(x: Int): Int {
        if (parent[x] != x) {
            parent[x] = find(parent[x]) // Path compression
        }
        return parent[x]
    }

    fun union(x: Int, y: Int): Boolean {
        val rootX = find(x)
        val rootY = find(y)

        if (rootX == rootY) return false

        // Union by rank
        when {
            rank[rootX] < rank[rootY] -> parent[rootX] = rootY
            rank[rootX] > rank[rootY] -> parent[rootY] = rootX
            else -> {
                parent[rootY] = rootX
                rank[rootX]++
            }
        }

        return true
    }
}

private fun kruskalSpanningForest(pointCount: Int, edges: List<GraphEdge>): List<GraphEdge> {
    if (pointCount <= 1 || edges.isEmpty()) return emptyList()

    val sortedEdges = edges.sortedBy { it.weight }
    val uf = UnionFind(pointCount)
    val treeEdges = mutableListOf<GraphEdge>()

    for (edge in sortedEdges) {
        if (uf.union(edge.i, edge.j)) {
            treeEdges.add(edge)
            if (treeEdges.size == pointCount - 1) break
        }
    }

    return treeEdges
}

private fun isConnectedGraph(pointCount: Int, edges: List<GraphEdge>): Boolean {
    if (pointCount <= 1) return true
    if (edges.isEmpty()) return false

    val adjacency = Array(pointCount) { mutableListOf<Int>() }
    for (edge in edges) {
        adjacency[edge.i].add(edge.j)
        adjacency[edge.j].add(edge.i)
    }

    val visited = BooleanArray(pointCount)
    val queue = ArrayDeque<Int>()
    visited[0] = true
    queue.addLast(0)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (neighbor in adjacency[current]) {
            if (visited[neighbor]) continue
            visited[neighbor] = true
            queue.addLast(neighbor)
        }
    }

    return visited.all { it }
}

private fun adaptiveKnnSpanningTree(
    pointCount: Int,
    knnEdges: (k: Int) -> List<GraphEdge>
): PointGraph {
    if (pointCount <= 1) return PointGraph(emptyList(), pointCount)

    var k = 1
    var candidateEdges = knnEdges(k)

    while (k < pointCount - 1 && !isConnectedGraph(pointCount, candidateEdges)) {
        k = min(pointCount - 1, k * 2)
        candidateEdges = knnEdges(k)
    }

    val treeEdges = kruskalSpanningForest(pointCount, candidateEdges)
    require(treeEdges.size == pointCount - 1) {
        "adaptiveKnnSpanningTree: failed to build a spanning tree for $pointCount points"
    }
    return PointGraph(treeEdges, pointCount)
}

// ==================== Additional JTS Wrappers ====================

/**
 * Represents a convex hull of a point set.
 *
 * @param hullPoints List of points forming the convex hull in counter-clockwise order
 * @param hullIndices Indices of hull points in the original point list
 */
data class ConvexHull2D(
    val hullPoints: List<Vec2>,
    val hullIndices: List<Int>
)

/**
 * Compute the convex hull of 2D points using JTS.
 *
 * The convex hull is the smallest convex polygon containing all points.
 *
 * @param points List of 2D points
 * @return ConvexHull2D with hull vertices
 */
fun vec2ConvexHull(points: List<Vec2>): ConvexHull2D {
    require(points.isNotEmpty()) { "vec2ConvexHull: points must not be empty" }
    validatePoints2D(points, "vec2ConvexHull")

    if (points.size < 3) {
        return ConvexHull2D(points.toList(), points.indices.toList())
    }

    // Convert to JTS coordinates
    val coords = points.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }

    // Compute convex hull
    val geomFactory = GeometryFactory()
    val multiPoint = geomFactory.createMultiPointFromCoords(coords.toTypedArray())
    val hull = multiPoint.convexHull()

    // Extract hull vertices and find their indices
    val hullCoords = hull.coordinates
    val hullPoints = mutableListOf<Vec2>()
    val hullIndices = mutableListOf<Int>()

    for (hullCoord in hullCoords) {
        // Skip the duplicate closing point
        if (hullPoints.isNotEmpty() &&
            hullCoord.equals2D(hullCoords[0])) {
            continue
        }

        val idx = coords.indexOfFirst { it.equals2D(hullCoord) }
        if (idx >= 0) {
            hullPoints.add(points[idx])
            hullIndices.add(idx)
        }
    }

    return ConvexHull2D(hullPoints, hullIndices)
}

/**
 * Compute a graph connecting convex hull points in order.
 *
 * Creates edges along the boundary of the convex hull.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph with convex hull boundary edges
 */
fun vec2ConvexHullGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2ConvexHullGraph: points must not be empty" }
    validatePoints2D(points, "vec2ConvexHullGraph")

    val hull = vec2ConvexHull(points)
    val edges = mutableListOf<GraphEdge>()

    // Connect consecutive hull points
    for (i in hull.hullIndices.indices) {
        val curr = hull.hullIndices[i]
        val next = hull.hullIndices[(i + 1) % hull.hullIndices.size]

        val dist = computeDistance2D(
            points[curr],
            points[next],
            options,
            curr,
            next,
            points
        )
        edges.add(GraphEdge(curr, next, dist))
    }

    return PointGraph(edges, points.size)
}

/**
 * Compute triangles from Delaunay triangulation.
 *
 * @param points List of 2D points
 * @return List of triangles, where each triangle is a triple of point indices
 */
fun vec2DelaunayTriangles(points: List<Vec2>): List<Triple<Int, Int, Int>> {
    require(points.isNotEmpty()) { "vec2DelaunayTriangles: points must not be empty" }
    validatePoints2D(points, "vec2DelaunayTriangles")

    if (points.size < 3) return emptyList()

    // Convert to JTS coordinates
    val coords = points.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }

    // Build Delaunay triangulation
    val builder = DelaunayTriangulationBuilder()
    builder.setSites(coords)
    val triangles = builder.getTriangles(GeometryFactory())

    // Extract triangles
    val result = mutableListOf<Triple<Int, Int, Int>>()

    for (i in 0 until triangles.numGeometries) {
        val triangle = triangles.getGeometryN(i)
        val triCoords = triangle.coordinates

        if (triCoords.size >= 4) {  // Triangle has 4 coords (last = first)
            val idx0 = coords.indexOfFirst { it.equals2D(triCoords[0]) }
            val idx1 = coords.indexOfFirst { it.equals2D(triCoords[1]) }
            val idx2 = coords.indexOfFirst { it.equals2D(triCoords[2]) }

            if (idx0 >= 0 && idx1 >= 0 && idx2 >= 0) {
                result.add(Triple(idx0, idx1, idx2))
            }
        }
    }

    return result
}

/**
 * Compute the nearest point on the convex hull for each interior point.
 *
 * @param points List of 2D points
 * @param options Graph connectivity options
 * @return PointGraph connecting each interior point to its nearest hull point
 */
fun vec2HullProjectionGraph(
    points: List<Vec2>,
    options: GraphConnectivityOptions = GraphConnectivityOptions()
): PointGraph {
    require(points.isNotEmpty()) { "vec2HullProjectionGraph: points must not be empty" }
    validatePoints2D(points, "vec2HullProjectionGraph")

    val hull = vec2ConvexHull(points)
    val hullSet = hull.hullIndices.toSet()
    val edges = mutableListOf<GraphEdge>()

    // For each non-hull point, connect to nearest hull point
    for (i in points.indices) {
        if (i in hullSet) continue

        var nearestHullIdx = hull.hullIndices[0]
        var minDist = Double.POSITIVE_INFINITY

        for (hullIdx in hull.hullIndices) {
            val dist = computeDistance2D(
                points[i],
                points[hullIdx],
                options,
                i,
                hullIdx,
                points
            )
            if (dist < minDist) {
                minDist = dist
                nearestHullIdx = hullIdx
            }
        }

        edges.add(GraphEdge(i, nearestHullIdx, minDist))
    }

    return PointGraph(edges, points.size)
}
