package afield.examples.stdlib

import afield.stdlib.DynamicEdgeList
import afield.stdlib.DynamicGraphSystem
import afield.stdlib.DynamicPointState
import afield.stdlib.GraphPathSetResult
import afield.stdlib.PointGraph
import afield.stdlib.Vec3
import afield.stdlib.Vec3AlphaEventHypergraphCollapseSystem
import afield.stdlib.Vec3BreathingAnisotropicKnnSystem
import afield.stdlib.Vec3FlowlineAdvectionGraphSystem
import afield.stdlib.Vec3LocalTangentTriangulationSystem
import afield.stdlib.Vec3PersistentProximityHysteresisSystem
import afield.stdlib.Vec3SignalRoutingGraphSystem
import afield.stdlib.Vec3SpatialHash
import afield.stdlib.Vec4
import afield.stdlib.graphAnchoredGreedyThenDiameterCover
import afield.stdlib.stepDynamicGraphWithExponentialDecay

/**
 * Helper to convert external data into the dynamic graph state used by the examples.
 */
fun dynamicStateOf(
    points: List<Vec3>,
    labels: List<Vec4>? = null,
    time: Float = 0f
): DynamicPointState = DynamicPointState(points.toMutableList(), labels, time)

/**
 * Example: use a dynamic graph system, then snapshot into PointGraph so existing
 * traversal / cover / diameter / A* code can operate unchanged.
 */
fun exampleDynamicGraphPipeline(
    points: List<Vec3>,
    labels: List<Vec4>? = null
): PointGraph {
    val state = dynamicStateOf(points, labels)
    val edges = DynamicEdgeList(pointCount = state.pointCount, undirected = true)
    val spatialHash = Vec3SpatialHash(cellSize = 0.15)

    val system: DynamicGraphSystem = Vec3FlowlineAdvectionGraphSystem(
        influenceRadius = 0.25,
        stepLength = 0.12,
        ttl = 2.0f,
        reinforce = 1.2,
        maxWeight = 8.0,
        swirlMix = 0.45
    )

    val dt = 1f / 60f
    repeat(60) {
        stepDynamicGraphWithExponentialDecay(
            dt = dt,
            state = state,
            edges = edges,
            spatialHash = spatialHash,
            system = system,
            lambda = 0.35
        )
    }

    return edges.toPointGraph(minWeight = 0.05)
}

/**
 * Example: switch systems over time while keeping the same dynamic edge store.
 */
fun exampleSystemSwitching(
    points: List<Vec3>,
    labels: List<Vec4>? = null
): PointGraph {
    val state = dynamicStateOf(points, labels)
    val edges = DynamicEdgeList(pointCount = state.pointCount, undirected = true)
    val spatialHash = Vec3SpatialHash(cellSize = 0.18)

    val systems = listOf<DynamicGraphSystem>(
        Vec3BreathingAnisotropicKnnSystem(
            k = 8,
            ttl = 2.5f,
            reinforce = 1.5,
            maxWeight = 8.0,
            mutualOnly = true,
            queryRadius = 0.45
        ),
        Vec3PersistentProximityHysteresisSystem(
            rEnter = 0.12,
            rExit = 0.18,
            ttl = 3.0f,
            reinforce = 2.0,
            maxWeight = 8.0,
            labelBias = 0.3
        ),
        Vec3SignalRoutingGraphSystem(
            neighborhoodK = 12,
            ttl = 2.5f,
            reinforce = 1.0,
            maxWeight = 8.0,
            routeCount = 2,
            maxNeighborRadius = 0.40
        ),
        Vec3LocalTangentTriangulationSystem(
            neighborhoodK = 10,
            ttl = 2.0f,
            reinforce = 0.8,
            maxWeight = 8.0,
            maxNeighborRadius = 0.35
        ),
        Vec3AlphaEventHypergraphCollapseSystem(
            neighborhoodK = 10,
            alphaRadius = 0.22,
            ttl = 2.3f,
            reinforcePerUnitArea = 1.1,
            maxWeight = 8.0,
            maxNeighborRadius = 0.30
        )
    )

    val dt = 1f / 60f
    repeat(150) { frame ->
        val system = systems[(frame / 30) % systems.size]
        stepDynamicGraphWithExponentialDecay(
            dt = dt,
            state = state,
            edges = edges,
            spatialHash = spatialHash,
            system = system,
            lambda = 0.35
        )
    }

    return edges.toPointGraph(minWeight = 0.05)
}

/**
 * Example: snapshot into PointGraph, then reuse the existing path utilities.
 */
fun exampleDynamicGraphToPathCover(
    points: List<Vec3>,
    labels: List<Vec4>? = null,
    anchor: Int = 0
): GraphPathSetResult {
    val graph = exampleDynamicGraphPipeline(points, labels)
    return graphAnchoredGreedyThenDiameterCover(graph, anchor)
}