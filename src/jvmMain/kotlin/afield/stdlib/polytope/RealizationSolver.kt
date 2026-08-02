package afield.stdlib.polytope

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.Vec3
import afield.stdlib.leastSquares
import kotlin.math.max
import kotlin.math.sqrt

/** Weights are residual multipliers; set a term to zero to disable that constraint family. */
data class RealizationSolverOptions(
    val edgeLengthWeight: Double = 1.0,
    val facePlanarityWeight: Double = 2.0,
    val centroidWeight: Double = 4.0,
    val radiusWeight: Double = 0.2,
    val referenceWeight: Double = 1e-3,
    val targetEdgeLength: Double? = null,
    val leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 96),
) {
    init {
        require(edgeLengthWeight.isFinite() && edgeLengthWeight >= 0.0)
        require(facePlanarityWeight.isFinite() && facePlanarityWeight >= 0.0)
        require(centroidWeight.isFinite() && centroidWeight >= 0.0)
        require(radiusWeight.isFinite() && radiusWeight >= 0.0)
        require(referenceWeight.isFinite() && referenceWeight >= 0.0)
        require(targetEdgeLength == null || targetEdgeLength.isFinite() && targetEdgeLength > 0.0)
        require(edgeLengthWeight + facePlanarityWeight + centroidWeight + radiusWeight + referenceWeight > 0.0) {
            "at least one realization constraint must be enabled"
        }
    }
}

data class RealizationDiagnostics(
    val edgeLengthMean: Double,
    val edgeLengthRmsDeviation: Double,
    val maxFacePlanarityError: Double,
    val facePlanarityRmsError: Double,
    val centroid: Vec3,
    val degenerateFaces: List<ElementId>,
)

data class RealizationSolveResult(
    val polyhedron: PolyhedralObject,
    val initialDiagnostics: RealizationDiagnostics,
    val finalDiagnostics: RealizationDiagnostics,
    val converged: Boolean,
    val iterations: Int,
    val rmsResidual: Double,
    val reason: String,
)

/**
 * Improve a supplied embedding without changing its authoritative topology or attributes.
 *
 * This is a general constraint relaxation, not a claim of a unique Conway "canonical form". The
 * defaults are useful for the symmetric finite polyhedra produced here. Disable common-radius or
 * common-edge constraints for deliberately irregular objects.
 */
fun PolyhedralObject.solveRealization(
    options: RealizationSolverOptions = RealizationSolverOptions(),
): RealizationSolveResult {
    val initialRealization = realization ?: throw IllegalStateException("realization solving requires initial 3D coordinates")
    topology.validate().throwIfInvalid()
    val vertices = topology.elements(0)
    require(vertices.isNotEmpty()) { "realization solving requires at least one vertex" }
    val vertexIndex = vertices.mapIndexed { index, vertex -> vertex.id.value to index }.toMap()
    val initial = DoubleArray(vertices.size * 3)
    for (i in vertices.indices) {
        initial[3 * i] = initialRealization.x(i).toDouble()
        initial[3 * i + 1] = initialRealization.y(i).toDouble()
        initial[3 * i + 2] = initialRealization.z(i).toDouble()
    }
    require(initial.all(Double::isFinite)) { "initial realization contains non-finite coordinates" }

    val edges = topology.elements(1).map { edge ->
        val endpoints = topology.incidentDown(edge.id)
        require(endpoints.size == 2) { "edge ${edge.id.value} does not have exactly two endpoints" }
        vertexIndex.getValue(endpoints[0]) to vertexIndex.getValue(endpoints[1])
    }
    val faces = topology.elements(2).map { face ->
        face.id to topology.faceCycle(face.id).map { vertexIndex.getValue(it) }.toIntArray()
    }
    val initialCenter = centroid(initial)
    val initialEdgeLengths = edges.map { (a, b) -> distance(initial, a, b) }
    val targetEdgeLength = options.targetEdgeLength ?: initialEdgeLengths.average()
    val targetRadius = vertices.indices.map { distance(initial, it, initialCenter) }.average()
    val initialDiagnostics = geometryDiagnostics()

    val fit = leastSquares(initial, options.leastSquares) { x ->
        buildList {
            if (options.edgeLengthWeight > 0.0) for ((a, b) in edges) {
                add(options.edgeLengthWeight * (distance(x, a, b) - targetEdgeLength))
            }
            if (options.facePlanarityWeight > 0.0) for ((_, face) in faces) {
                val plane = facePlane(x, face)
                for (vertex in face) add(options.facePlanarityWeight * signedPlaneDistance(x, vertex, plane))
            }
            val center = centroid(x)
            if (options.centroidWeight > 0.0) for (axis in 0..2) {
                add(options.centroidWeight * (center[axis] - initialCenter[axis]))
            }
            if (options.radiusWeight > 0.0) for (vertex in vertices.indices) {
                add(options.radiusWeight * (distance(x, vertex, center) - targetRadius))
            }
            if (options.referenceWeight > 0.0) for (i in x.indices) {
                add(options.referenceWeight * (x[i] - initial[i]))
            }
        }.toDoubleArray()
    }
    val solved = Realization3(vertices.indices.map { i ->
        Vec3(fit.x[3 * i].toFloat(), fit.x[3 * i + 1].toFloat(), fit.x[3 * i + 2].toFloat())
    })
    val output = copy(realization = solved)
    return RealizationSolveResult(
        output,
        initialDiagnostics,
        output.geometryDiagnostics(),
        fit.converged,
        fit.iterations,
        fit.rms,
        fit.reason,
    )
}

fun PolyhedralObject.geometryDiagnostics(): RealizationDiagnostics {
    val realization = realization ?: throw IllegalStateException("geometry diagnostics require a 3D realization")
    val vertices = topology.elements(0)
    val vertexIndex = vertices.mapIndexed { index, vertex -> vertex.id.value to index }.toMap()
    val x = DoubleArray(vertices.size * 3)
    for (i in vertices.indices) {
        x[3 * i] = realization.x(i).toDouble()
        x[3 * i + 1] = realization.y(i).toDouble()
        x[3 * i + 2] = realization.z(i).toDouble()
    }
    val edgeLengths = topology.elements(1).map { edge ->
        val endpoints = topology.incidentDown(edge.id)
        distance(x, vertexIndex.getValue(endpoints[0]), vertexIndex.getValue(endpoints[1]))
    }
    val edgeMean = edgeLengths.average()
    val edgeRms = sqrt(edgeLengths.sumOf { (it - edgeMean) * (it - edgeMean) } / max(1, edgeLengths.size))
    val planarity = mutableListOf<Double>()
    val degenerate = mutableListOf<ElementId>()
    for (face in topology.elements(2)) {
        val cycle = topology.faceCycle(face.id).map { vertexIndex.getValue(it) }.toIntArray()
        val plane = facePlane(x, cycle)
        if (plane.normalLength <= 1e-10) degenerate += face.id
        cycle.forEach { planarity += kotlin.math.abs(signedPlaneDistance(x, it, plane)) }
    }
    return RealizationDiagnostics(
        edgeMean,
        edgeRms,
        planarity.maxOrNull() ?: 0.0,
        sqrt(planarity.sumOf { it * it } / max(1, planarity.size)),
        centroid(x).let { Vec3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) },
        degenerate,
    )
}

private data class Plane(val cx: Double, val cy: Double, val cz: Double, val nx: Double, val ny: Double, val nz: Double, val normalLength: Double)

private fun facePlane(x: DoubleArray, face: IntArray): Plane {
    var cx = 0.0
    var cy = 0.0
    var cz = 0.0
    var nx = 0.0
    var ny = 0.0
    var nz = 0.0
    for (i in face.indices) {
        val p = face[i]
        val q = face[(i + 1) % face.size]
        val px = x[3 * p]
        val py = x[3 * p + 1]
        val pz = x[3 * p + 2]
        val qx = x[3 * q]
        val qy = x[3 * q + 1]
        val qz = x[3 * q + 2]
        cx += px
        cy += py
        cz += pz
        nx += (py - qy) * (pz + qz)
        ny += (pz - qz) * (px + qx)
        nz += (px - qx) * (py + qy)
    }
    val count = face.size.toDouble()
    return Plane(cx / count, cy / count, cz / count, nx, ny, nz, sqrt(nx * nx + ny * ny + nz * nz))
}

private fun signedPlaneDistance(x: DoubleArray, vertex: Int, plane: Plane): Double {
    val denominator = max(plane.normalLength, 1e-10)
    return (
        (x[3 * vertex] - plane.cx) * plane.nx +
            (x[3 * vertex + 1] - plane.cy) * plane.ny +
            (x[3 * vertex + 2] - plane.cz) * plane.nz
        ) / denominator
}

private fun centroid(x: DoubleArray): DoubleArray {
    val center = DoubleArray(3)
    val count = x.size / 3
    for (vertex in 0 until count) for (axis in 0..2) center[axis] += x[3 * vertex + axis]
    for (axis in 0..2) center[axis] /= count
    return center
}

private fun distance(x: DoubleArray, a: Int, b: Int): Double {
    val dx = x[3 * a] - x[3 * b]
    val dy = x[3 * a + 1] - x[3 * b + 1]
    val dz = x[3 * a + 2] - x[3 * b + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun distance(x: DoubleArray, vertex: Int, point: DoubleArray): Double {
    val dx = x[3 * vertex] - point[0]
    val dy = x[3 * vertex + 1] - point[1]
    val dz = x[3 * vertex + 2] - point[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}
