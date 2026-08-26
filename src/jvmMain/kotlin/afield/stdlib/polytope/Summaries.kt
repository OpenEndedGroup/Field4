@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.Vec3

/**
 * Small, plain data classes that reduce the library's big values to something worth reading.
 *
 * These exist because of the architectural boundary: the mathematical types contain no HTML and know
 * nothing about presentation. `PolytopeVisualizers.kt` registers a value printer for each big type
 * that reduces it to one of these summaries and hands *that* to Field's ordinary reflective
 * visualizer. So a bare `polyhedron` or `result` as a box's last expression is informative, and the
 * same facts are available programmatically — `println(result.summary())` reports exactly what the
 * inspector shows, because it is the same call.
 *
 * ## What to trust in them
 *
 * They are lossy by design and a few of the losses matter:
 *
 * - **A summary is a *snapshot*, not a view.** Every one of these is computed eagerly from the value
 *   at the moment you ask, and several are not cheap: [PolytopeTopology.summary] runs the validator,
 *   twice for a valid rank-3 topology.
 * - **Counts of things are exact; the *identity* of things is gone.** [PolytopeTopologySummary]
 *   reports how many faces have each degree but not which; [SymmetryDeviation] reports the worst
 *   spread but not the class it came from. Go back to the underlying value for that.
 * - **A `null` almost always means "not measurable" rather than "no".**
 *   [PolytopeTopologySummary.closedManifold] is null when the check was not run, and
 *   [Realization3Summary]'s bounds are null for an empty realization.
 * - **Names are used as keys, so same-named things collapse.** [RelaxationPlanSummary.weights] is
 *   keyed by constraint family name and [PolytopeTransformSummary] lists operator display names, so
 *   two edge-length families with different targets, or two distinct operators printing the same
 *   name, are indistinguishable in the summary.
 */

/** Compact, stable inspection value for a rank-aware incidence topology. */
data class PolytopeTopologySummary(
    val rank: Int,
    /** Element count per rank, `0..rank` inclusive — so the whole-polytope element is counted here. */
    val elementCounts: Map<Int, Int>,
    /**
     * How many faces have each corner count, ordered by degree. Faces with no stored ordered boundary
     * are silently omitted, so this can total fewer than `elementCounts[2]`.
     */
    val faceDegreeCounts: Map<Int, Int>,
    /** Alternating sum over the *proper* ranks; the whole-polytope rank is excluded. */
    val eulerCharacteristic: Int,
    /**
     * Inner boundary rings summed over every face — 0 for everything the seeds and the Conway
     * operators produce, so a non-zero number here is why [closedManifold] is false.
     */
    val faceHoleCount: Int,
    /**
     * [eulerCharacteristic] with one subtracted per hole: a face with `h` holes is a disk with `h`
     * disks removed. Equal to [eulerCharacteristic] whenever [faceHoleCount] is 0.
     */
    val cellularEulerCharacteristic: Int,
    /** Basic incidence and boundary validation only. */
    val valid: Boolean,
    /** Null when the stricter every-edge-has-two-faces check was not run, not when it failed. */
    val closedManifold: Boolean?,
)

/** Bounds and centre of an immutable ordinary 3D realization. */
data class Realization3Summary(
    val vertexCount: Int,
    /** Null exactly when there are no vertices; otherwise a fresh [Vec3] the caller owns. */
    val boundsMin: Vec3?,
    val boundsMax: Vec3?,
    /** The mean vertex position — the vertex centroid, which is not the centre of the bounding box. */
    val centroid: Vec3?,
)

/** The parts of a complete polyhedral object that are most useful at a live-coding prompt. */
data class PolyhedralObjectSummary(
    val topology: PolytopeTopologySummary,
    /** Null when the object is still abstract — a real state, not a failure. */
    val realization: Realization3Summary?,
    /** How many elements carry any attribute at all, a name set to null included. */
    val attributedElements: Int,
    /** Every attribute name in use anywhere on the object, sorted; values are not summarised. */
    val attributeKeys: Set<String>,
)

/** Structured validation output without exposing the validator's immutable wrapper internals. */
data class PolytopeValidationSummary(
    val valid: Boolean,
    val issueCount: Int,
    /** Issue class simple names to counts, sorted — the shape of the failure, not its location. */
    val issueTypes: Map<String, Int>,
    /** One message per issue, in the order the validator found them; these do name elements. */
    val messages: List<String>,
)

/** Min/max inspection of one scalar parameter resolved over a transform deployment. */
data class ScalarParameterSummary(
    val name: String,
    /** How many elements resolved a value for [name], which need not be every selected element. */
    val count: Int,
    val min: Double,
    val max: Double,
)

/** Compact replay information for one primitive Conway deployment. */
data class TransformDeploymentSummary(
    val operation: String,
    /** Null means the operator's whole natural domain; a number means an explicit selection. */
    val selectedCount: Int?,
    /** Selected elements per rank — the evidence that a selector hit the rank you expected. */
    val selectedRanks: Map<Int, Int>,
    val scalarParameters: List<ScalarParameterSummary>,
)

/** Provenance-aware summary of a complete transform chain and its output. */
data class PolytopeTransformSummary(
    /** What the caller asked for, named composites retained: `bevel`, not its primitive expansion. */
    val requestedOperations: List<String>,
    /** The same chain fully expanded into primitives; this is what actually ran. */
    val primitiveOperations: List<String>,
    val output: PolyhedralObjectSummary,
    /** One per primitive step, so this aligns with [primitiveOperations] and not with the requests. */
    val deployments: List<TransformDeploymentSummary>,
)

/** What a realization run was actually asked to do: named families, weights, and normalization. */
data class RelaxationPlanSummary(
    /** Family names in plan order, including any weighted zero — a plan is a declaration, not a run. */
    val constraints: List<String>,
    /** Keyed by family name, so two families sharing a name collapse to one entry. */
    val weights: Map<String, Double>,
    val maxIterations: Int,
    /** The gauge as `centre / scale -> target`, or `none` when the answer is left un-normalized. */
    val gauge: String,
)

/** Rendering-derived counts while retaining the polygon-to-triangle provenance distinction. */
data class TriangulatedGeometrySummary(
    /** Mesh vertices, which exceed the abstract vertex count because corners are duplicated per face. */
    val renderVertices: Int,
    val triangles: Int,
    /** Distinct polygon faces that produced triangles. */
    val sourcePolygonFaces: Int,
    /** Distinct abstract vertices behind those render vertices — the deduplicated figure. */
    val sourceVertexElements: Int,
    val hasFlatNormals: Boolean,
)

/**
 * Counts, face-degree histogram, Euler characteristic and validity in one value.
 *
 * Runs the validator to fill [PolytopeTopologySummary.valid], and a second stricter pass for
 * [PolytopeTopologySummary.closedManifold] when the topology is rank 3 or above and already valid.
 * That is two full validations, so this is inspection rather than something to call per frame.
 */
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
        faceHoleCount = totalFaceHoleCount(),
        cellularEulerCharacteristic = cellularEulerCharacteristic(),
        valid = basic.isValid,
        closedManifold = if (rank >= 3 && basic.isValid) validate(checkManifold = true).isValid else null,
    )
}

/** Axis-aligned bounds and the vertex centroid, in one pass. All three are null for no vertices. */
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

/**
 * Topology, geometry and attribute overview of a whole object — the value Field prints for a bare
 * `polyhedron`.
 *
 * Inherits [PolytopeTopology.summary]'s validation cost. Attributes are summarised by *name* only:
 * the keys tell you a field has been painted, never what is in it.
 */
fun PolyhedralObject.summary(): PolyhedralObjectSummary {
    val attributed = attributes.elementIds
    return PolyhedralObjectSummary(
        topology = topology.summary(),
        realization = realization?.summary(),
        attributedElements = attributed.size,
        attributeKeys = attributed.flatMapTo(sortedSetOf()) { attributes[it].keys },
    )
}

/**
 * Issue counts grouped by kind, plus every message.
 *
 * Reformats an already-computed result; it does not re-validate, so it costs nothing and cannot
 * disagree with the [ValidationResult] it came from.
 */
fun ValidationResult.summary(): PolytopeValidationSummary = PolytopeValidationSummary(
    valid = isValid,
    issueCount = issues.size,
    issueTypes = issues.groupingBy { it::class.simpleName ?: "ValidationIssue" }.eachCount().toSortedMap(),
    messages = issues.map { it.message },
)

/**
 * What one primitive operator was actually deployed over: how many sources it took, at which ranks,
 * and the range of every scalar it resolved.
 *
 * The min/max pair is the cheap check that a parameter field did something — a non-uniform `kis`
 * whose `heightScale` reports an identical min and max was uniform after all.
 */
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

/**
 * The whole chain: what was asked for, what primitives ran, what came out, and how each step was
 * deployed.
 *
 * The two operation lists differ whenever a named composite was used, and that difference is the
 * point — `bevel` appears once in the requests and as its expansion in the primitives.
 */
fun PolytopeTransformResult.summary(): PolytopeTransformSummary = PolytopeTransformSummary(
    requestedOperations = notationHistory.map { it.displayName },
    primitiveOperations = history.map { it.displayName },
    output = polyhedron.summary(),
    deployments = deployments.map { it.summary() },
)

/**
 * The plan as declared — families, weights, iteration cap, gauge — and nothing about a solve.
 *
 * This is what makes a realization run diffable before it is run: print two plans and the difference
 * is the experiment. Names are the keys, so a plan carrying two families of the same name reports
 * only one weight.
 */
fun RelaxationPlan.summary(): RelaxationPlanSummary = RelaxationPlanSummary(
    constraints = constraints.map { it.name },
    weights = constraints.associate { it.name to it.weight },
    maxIterations = leastSquares.maxIterations,
    gauge = gauge?.let { "${it.centre} / ${it.scale} -> ${it.scaleTarget}" } ?: "none",
)

/**
 * Render-side counts beside the abstract ones they came from.
 *
 * Reading [TriangulatedGeometrySummary.renderVertices] against
 * [TriangulatedGeometrySummary.sourceVertexElements] is the whole reason this summary exists: flat
 * shading requires a corner per face, so the mesh has many more vertices than the topology, and the
 * provenance maps are what tie them back.
 */
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
