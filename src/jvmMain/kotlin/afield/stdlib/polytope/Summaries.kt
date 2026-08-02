package afield.stdlib.polytope

import afield.stdlib.Vec3

/** Compact, stable inspection value for a rank-aware incidence topology. */
data class PolytopeTopologySummary(
    val rank: Int,
    val elementCounts: Map<Int, Int>,
    val faceDegreeCounts: Map<Int, Int>,
    val eulerCharacteristic: Int,
    val valid: Boolean,
    val closedManifold: Boolean?,
)

/** Bounds and centre of an immutable ordinary 3D realization. */
data class Realization3Summary(
    val vertexCount: Int,
    val boundsMin: Vec3?,
    val boundsMax: Vec3?,
    val centroid: Vec3?,
)

/** The parts of a complete polyhedral object that are most useful at a live-coding prompt. */
data class PolyhedralObjectSummary(
    val topology: PolytopeTopologySummary,
    val realization: Realization3Summary?,
    val attributedElements: Int,
    val attributeKeys: Set<String>,
)

/** Structured validation output without exposing the validator's immutable wrapper internals. */
data class PolytopeValidationSummary(
    val valid: Boolean,
    val issueCount: Int,
    val issueTypes: Map<String, Int>,
    val messages: List<String>,
)

/** Min/max inspection of one scalar parameter resolved over a transform deployment. */
data class ScalarParameterSummary(
    val name: String,
    val count: Int,
    val min: Double,
    val max: Double,
)

/** Compact replay information for one primitive Conway deployment. */
data class TransformDeploymentSummary(
    val operation: String,
    val selectedCount: Int?,
    val selectedRanks: Map<Int, Int>,
    val scalarParameters: List<ScalarParameterSummary>,
)

/** Provenance-aware summary of a complete transform chain and its output. */
data class PolytopeTransformSummary(
    val requestedOperations: List<String>,
    val primitiveOperations: List<String>,
    val output: PolyhedralObjectSummary,
    val deployments: List<TransformDeploymentSummary>,
)

/** What a realization run was actually asked to do: named families, weights, and normalization. */
data class RelaxationPlanSummary(
    val constraints: List<String>,
    val weights: Map<String, Double>,
    val maxIterations: Int,
    val gauge: String,
)

/** Rendering-derived counts while retaining the polygon-to-triangle provenance distinction. */
data class TriangulatedGeometrySummary(
    val renderVertices: Int,
    val triangles: Int,
    val sourcePolygonFaces: Int,
    val sourceVertexElements: Int,
    val hasFlatNormals: Boolean,
)

fun PolytopeTopology.summary(): PolytopeTopologySummary {
    val basic = validate()
    return PolytopeTopologySummary(
        rank = rank,
        elementCounts = (0..rank).associateWith { elements(it).size },
        faceDegreeCounts = elements(2)
            .mapNotNull { faceCycleOrNull(it.id)?.size }
            .groupingBy { it }
            .eachCount()
            .toSortedMap(),
        eulerCharacteristic = eulerCharacteristic(),
        valid = basic.isValid,
        closedManifold = if (rank >= 3 && basic.isValid) validate(checkManifold = true).isValid else null,
    )
}

fun Realization3.summary(): Realization3Summary {
    val coordinates = vertexCoordinates
    if (coordinates.isEmpty()) return Realization3Summary(0, null, null, null)
    val min = Vec3(coordinates.first())
    val max = Vec3(coordinates.first())
    val center = Vec3()
    for (coordinate in coordinates) {
        min.x = kotlin.math.min(min.x, coordinate.x)
        min.y = kotlin.math.min(min.y, coordinate.y)
        min.z = kotlin.math.min(min.z, coordinate.z)
        max.x = kotlin.math.max(max.x, coordinate.x)
        max.y = kotlin.math.max(max.y, coordinate.y)
        max.z = kotlin.math.max(max.z, coordinate.z)
        center.add(coordinate)
    }
    center.mul(1f / coordinates.size)
    return Realization3Summary(coordinates.size, min, max, center)
}

fun PolyhedralObject.summary(): PolyhedralObjectSummary {
    val attributed = attributes.elementIds
    return PolyhedralObjectSummary(
        topology = topology.summary(),
        realization = realization?.summary(),
        attributedElements = attributed.size,
        attributeKeys = attributed.flatMapTo(sortedSetOf()) { attributes[it].keys },
    )
}

fun ValidationResult.summary(): PolytopeValidationSummary = PolytopeValidationSummary(
    valid = isValid,
    issueCount = issues.size,
    issueTypes = issues.groupingBy { it::class.simpleName ?: "ValidationIssue" }.eachCount().toSortedMap(),
    messages = issues.map { it.message },
)

fun TransformDeployment.summary(): TransformDeploymentSummary {
    val selected = selectedSources
    val resolved = scalarParameters
    val parameterNames = resolved.values.flatMapTo(sortedSetOf()) { it.keys }
    return TransformDeploymentSummary(
        operation = operation.displayName,
        selectedCount = selected?.size,
        selectedRanks = selected.orEmpty().groupingBy { it.rank }.eachCount().toSortedMap(),
        scalarParameters = parameterNames.map { name ->
            val values = resolved.values.mapNotNull { it[name] }
            ScalarParameterSummary(name, values.size, values.minOrNull()!!, values.maxOrNull()!!)
        },
    )
}

fun PolytopeTransformResult.summary(): PolytopeTransformSummary = PolytopeTransformSummary(
    requestedOperations = notationHistory.map { it.displayName },
    primitiveOperations = history.map { it.displayName },
    output = polyhedron.summary(),
    deployments = deployments.map { it.summary() },
)

fun RelaxationPlan.summary(): RelaxationPlanSummary = RelaxationPlanSummary(
    constraints = constraints.map { it.name },
    weights = constraints.associate { it.name to it.weight },
    maxIterations = leastSquares.maxIterations,
    gauge = gauge?.let { "${it.centre} / ${it.scale} -> ${it.scaleTarget}" } ?: "none",
)

fun TriangulatedGeometry.summary(): TriangulatedGeometrySummary {
    val faceIds = triangleFaceIds
    val vertexIds = vertexElementIds
    return TriangulatedGeometrySummary(
        renderVertices = mesh.vertexCount,
        triangles = triangleCount,
        sourcePolygonFaces = faceIds.toSet().size,
        sourceVertexElements = vertexIds.toSet().size,
        hasFlatNormals = mesh.normals != null,
    )
}
