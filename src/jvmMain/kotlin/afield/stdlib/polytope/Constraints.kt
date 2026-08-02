package afield.stdlib.polytope

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The topology-derived facts every realization constraint needs, computed once per solve.
 *
 * This is the boundary that lets a realization run be a *declared set of constraints* rather than
 * one hardcoded residual blend. A constraint sees flat coordinate vectors and integer indices; it
 * never sees [ElementId] bookkeeping, [afield.graphics.FLine], triangles, or a renderer.
 *
 * Coordinates are packed as `x[3 * vertex + axis]`, indexed in exactly the same order as
 * `topology.elements(0)` and therefore as [Realization3].
 */
class RealizationProblem private constructor(
    val polyhedron: PolyhedralObject,
    internal val edgeEndpoints: IntArray,
    internal val faceCycles: List<IntArray>,
    internal val initial: DoubleArray,
) {
    val vertexCount: Int get() = initial.size / 3
    val edgeCount: Int get() = edgeEndpoints.size / 2
    val faceCount: Int get() = faceCycles.size

    /** A defensive copy of the embedding the problem was built from. */
    val initialCoordinates: DoubleArray get() = initial.copyOf()

    internal fun edgeA(edge: Int) = edgeEndpoints[2 * edge]
    internal fun edgeB(edge: Int) = edgeEndpoints[2 * edge + 1]

    /** Mean edge length of the initial embedding; the natural default for an unscaled target. */
    val initialMeanEdgeLength: Double by lazy {
        if (edgeCount == 0) 0.0
        else (0 until edgeCount).sumOf { distanceBetween(initial, edgeA(it), edgeB(it)) } / edgeCount
    }

    /** Mean distance from the initial vertex centroid to the initial vertices. */
    val initialMeanVertexRadius: Double by lazy {
        val centre = centroidOf(initial)
        if (vertexCount == 0) 0.0
        else (0 until vertexCount).sumOf { distanceToPoint(initial, it, centre) } / vertexCount
    }

    /** Mean distance from the origin to each initial edge's nearest point (the midsphere radius). */
    val initialMeanTangencyRadius: Double by lazy {
        if (edgeCount == 0) 0.0
        else {
            val point = DoubleArray(3)
            (0 until edgeCount).sumOf { edge ->
                edgeTangentPoint(initial, edgeA(edge), edgeB(edge), point)
                sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
            } / edgeCount
        }
    }

    /** The initial vertex centroid, as a fresh three-element array. */
    fun initialCentroid(): DoubleArray = centroidOf(initial)

    /**
     * The dual's face cycles, expressed in *this* object's face indices — the second half of the
     * polar-reciprocity relation [PolyhedralObject.canonicalize] iterates.
     *
     * `PolytopeTopology.dual()` maps rank-2 elements to rank-0 and rank-0 to rank-2 in element
     * order, so dual vertex `i` is this object's face `i` and dual face `i` is this object's vertex
     * `i`. Null when the abstract dual has no recoverable ordered boundaries, which is legal for a
     * non-manifold topology and simply means reciprocation is unavailable.
     */
    internal val dualFaceCycles: List<IntArray>? by lazy {
        runCatching {
            val dual = polyhedron.topology.dual()
            val dualVertexIndex = dual.elements(0).mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
            val cycles = dual.elements(2).map { face ->
                dual.faceCycle(face.id).map { dualVertexIndex.getValue(it) }.toIntArray()
            }
            if (cycles.size == vertexCount && dualVertexIndex.size == faceCount) cycles else null
        }.getOrNull()
    }

    companion object {
        /**
         * Build the index structures for [polyhedron]. Its topology must validate and it must
         * already carry a realization; use `realize(...)` first when it does not.
         */
        fun of(polyhedron: PolyhedralObject): RealizationProblem {
            val realization = polyhedron.realization
                ?: throw IllegalStateException("a realization problem requires initial 3D coordinates")
            polyhedron.topology.validate().throwIfInvalid()
            val vertices = polyhedron.topology.elements(0)
            require(vertices.isNotEmpty()) { "a realization problem requires at least one vertex" }
            val index = vertices.mapIndexed { at, vertex -> vertex.id.value to at }.toMap()

            val initial = DoubleArray(vertices.size * 3)
            for (i in vertices.indices) {
                initial[3 * i] = realization.x(i).toDouble()
                initial[3 * i + 1] = realization.y(i).toDouble()
                initial[3 * i + 2] = realization.z(i).toDouble()
            }
            require(initial.all(Double::isFinite)) { "initial realization contains non-finite coordinates" }

            val edges = polyhedron.topology.elements(1)
            val endpoints = IntArray(edges.size * 2)
            edges.forEachIndexed { at, edge ->
                val ends = polyhedron.topology.incidentDown(edge.id)
                require(ends.size == 2) { "edge ${edge.id.value} does not have exactly two endpoints" }
                endpoints[2 * at] = index.getValue(ends[0])
                endpoints[2 * at + 1] = index.getValue(ends[1])
            }
            val cycles = polyhedron.topology.elements(2).map { face ->
                polyhedron.topology.faceCycle(face.id).map { index.getValue(it) }.toIntArray()
            }
            return RealizationProblem(polyhedron, endpoints, cycles, initial)
        }
    }
}

/**
 * One named residual family.
 *
 * Constraints are immutable values, so a realization plan is data a caller can print, diff, and
 * reweight. [residualCount] must not depend on `x`, so a report can slice one run's residual vector
 * per family without re-running the solver.
 */
interface RealizationConstraint {
    /** Stable, human-readable family name. Used by [ConstraintResidualReport]. */
    val name: String

    /** Residual multiplier. Zero contributes zero residuals; it does not change [residualCount]. */
    val weight: Double

    /** How many residuals this family contributes for [problem]. Independent of the coordinates. */
    fun residualCount(problem: RealizationProblem): Int

    /** Write exactly [residualCount] weighted residuals into [into] starting at [offset]. */
    fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int)

    /** The same family at a different weight; used to report unweighted geometric error. */
    fun withWeight(weight: Double): RealizationConstraint
}

/** Every edge the same length. [target] defaults to the initial embedding's mean edge length. */
data class EdgeLengthConstraint(
    override val weight: Double = 1.0,
    val target: Double? = null,
) : RealizationConstraint {
    override val name get() = "edge-length"

    init {
        require(weight.isFinite() && weight >= 0.0) { "edge-length weight must be finite and non-negative" }
        require(target == null || (target.isFinite() && target > 0.0)) { "edge-length target must be positive" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.edgeCount

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val goal = target ?: problem.initialMeanEdgeLength
        for (edge in 0 until problem.edgeCount) {
            into[offset + edge] = weight * (distanceBetween(x, problem.edgeA(edge), problem.edgeB(edge)) - goal)
        }
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/** Every face corner on that face's own best-fit plane: one residual per face corner. */
data class FacePlanarityConstraint(
    override val weight: Double = 2.0,
) : RealizationConstraint {
    override val name get() = "face-planarity"

    init {
        require(weight.isFinite() && weight >= 0.0) { "face-planarity weight must be finite and non-negative" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.faceCycles.sumOf { it.size }

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        var at = offset
        for (cycle in problem.faceCycles) {
            val plane = facePlane(x, cycle)
            for (vertex in cycle) into[at++] = weight * signedPlaneDistance(x, vertex, plane)
        }
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/**
 * Pin the vertex centroid. [target] defaults to the initial centroid, which holds the object still
 * rather than moving it; pass `doubleArrayOf(0.0, 0.0, 0.0)` to also recentre it.
 */
data class CentroidConstraint(
    override val weight: Double = 4.0,
    val target: List<Double>? = null,
) : RealizationConstraint {
    override val name get() = "centroid"

    init {
        require(weight.isFinite() && weight >= 0.0) { "centroid weight must be finite and non-negative" }
        require(target == null || target.size == 3) { "centroid target must have three components" }
    }

    override fun residualCount(problem: RealizationProblem) = 3

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val goal = target ?: problem.initialCentroid().toList()
        val centre = centroidOf(x)
        for (axis in 0..2) into[offset + axis] = weight * (centre[axis] - goal[axis])
    }

    override fun withWeight(weight: Double) = copy(weight = weight)

    companion object {
        val AT_ORIGIN = CentroidConstraint(target = listOf(0.0, 0.0, 0.0))
    }
}

/**
 * Every vertex the same distance from the current vertex centroid — a circumsphere.
 *
 * This is the right constraint for the Platonic and Archimedean families and exactly the wrong one
 * for a deliberately irregular object, which is why it is a value the caller opts into.
 */
data class CommonRadiusConstraint(
    override val weight: Double = 0.2,
    val target: Double? = null,
) : RealizationConstraint {
    override val name get() = "common-radius"

    init {
        require(weight.isFinite() && weight >= 0.0) { "common-radius weight must be finite and non-negative" }
        require(target == null || (target.isFinite() && target > 0.0)) { "common-radius target must be positive" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.vertexCount

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val goal = target ?: problem.initialMeanVertexRadius
        val centre = centroidOf(x)
        for (vertex in 0 until problem.vertexCount) {
            into[offset + vertex] = weight * (distanceToPoint(x, vertex, centre) - goal)
        }
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/**
 * Stay near the embedding the problem started from. A small weight is a regulariser that picks one
 * representative out of the solution's rigid-motion null space; a large weight is a repair.
 */
data class ProximityConstraint(
    override val weight: Double = 1e-3,
) : RealizationConstraint {
    override val name get() = "proximity"

    init {
        require(weight.isFinite() && weight >= 0.0) { "proximity weight must be finite and non-negative" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.initial.size

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        for (i in x.indices) into[offset + i] = weight * (x[i] - problem.initial[i])
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/**
 * Every edge tangent to one sphere about the origin — the *midsphere* condition of the canonical
 * form. The residual is the distance from the origin to the edge line's nearest point, minus
 * [radius]; `null` uses the initial embedding's mean tangency radius, so the family constrains the
 * spread rather than the scale.
 *
 * The origin is deliberately not the vertex centroid: the canonical form centres on the *tangency*
 * points, and mixing the two centres is the classic way to make this constraint fight itself. Pair
 * it with [TangencyCentroidConstraint].
 */
data class EdgeTangencyConstraint(
    override val weight: Double = 1.0,
    val radius: Double? = null,
) : RealizationConstraint {
    override val name get() = "edge-tangency"

    init {
        require(weight.isFinite() && weight >= 0.0) { "edge-tangency weight must be finite and non-negative" }
        require(radius == null || (radius.isFinite() && radius > 0.0)) { "edge-tangency radius must be positive" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.edgeCount

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val goal = radius ?: problem.initialMeanTangencyRadius
        val point = DoubleArray(3)
        for (edge in 0 until problem.edgeCount) {
            edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
            val length = sqrt(point[0] * point[0] + point[1] * point[1] + point[2] * point[2])
            into[offset + edge] = weight * (length - goal)
        }
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/** The centroid of every edge's tangency point at the origin: the canonical form's centring. */
data class TangencyCentroidConstraint(
    override val weight: Double = 4.0,
) : RealizationConstraint {
    override val name get() = "tangency-centroid"

    init {
        require(weight.isFinite() && weight >= 0.0) { "tangency-centroid weight must be finite and non-negative" }
    }

    override fun residualCount(problem: RealizationProblem) = 3

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val sum = DoubleArray(3)
        val point = DoubleArray(3)
        for (edge in 0 until problem.edgeCount) {
            edgeTangentPoint(x, problem.edgeA(edge), problem.edgeB(edge), point)
            for (axis in 0..2) sum[axis] += point[axis]
        }
        val count = max(1, problem.edgeCount).toDouble()
        for (axis in 0..2) into[offset + axis] = weight * (sum[axis] / count)
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/** Unweighted magnitudes of one constraint family at one embedding. */
data class ConstraintResidualReport(
    val name: String,
    val weight: Double,
    val count: Int,
    val rms: Double,
    val max: Double,
)

/**
 * Evaluate every family at [x] and report its *unweighted* geometric error, so a reader sees the
 * residual in the units of the thing being constrained rather than in solver units.
 */
fun List<RealizationConstraint>.report(
    problem: RealizationProblem,
    x: DoubleArray = problem.initialCoordinates,
): List<ConstraintResidualReport> = map { constraint ->
    val unweighted = constraint.withWeight(1.0)
    val count = unweighted.residualCount(problem)
    val buffer = DoubleArray(count)
    if (count > 0) unweighted.residuals(problem, x, buffer, 0)
    ConstraintResidualReport(
        name = constraint.name,
        weight = constraint.weight,
        count = count,
        rms = if (count == 0) 0.0 else sqrt(buffer.sumOf { it * it } / count),
        max = buffer.maxOfOrNull(::abs) ?: 0.0,
    )
}

// ==============================================================================================
// Shared flat-array geometry. Internal on purpose: the public surface talks about polyhedra.
// ==============================================================================================

internal data class Plane(
    val cx: Double,
    val cy: Double,
    val cz: Double,
    val nx: Double,
    val ny: Double,
    val nz: Double,
    val normalLength: Double,
)

/** Newell centroid and area-weighted normal of an ordered polygon of coordinate indices. */
internal fun facePlane(x: DoubleArray, face: IntArray): Plane {
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

internal fun signedPlaneDistance(x: DoubleArray, vertex: Int, plane: Plane): Double {
    val denominator = max(plane.normalLength, 1e-10)
    return (
        (x[3 * vertex] - plane.cx) * plane.nx +
            (x[3 * vertex + 1] - plane.cy) * plane.ny +
            (x[3 * vertex + 2] - plane.cz) * plane.nz
        ) / denominator
}

internal fun centroidOf(x: DoubleArray): DoubleArray {
    val centre = DoubleArray(3)
    val count = x.size / 3
    if (count == 0) return centre
    for (vertex in 0 until count) for (axis in 0..2) centre[axis] += x[3 * vertex + axis]
    for (axis in 0..2) centre[axis] /= count
    return centre
}

internal fun distanceBetween(x: DoubleArray, a: Int, b: Int): Double {
    val dx = x[3 * a] - x[3 * b]
    val dy = x[3 * a + 1] - x[3 * b + 1]
    val dz = x[3 * a + 2] - x[3 * b + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

internal fun distanceToPoint(x: DoubleArray, vertex: Int, point: DoubleArray): Double {
    val dx = x[3 * vertex] - point[0]
    val dy = x[3 * vertex + 1] - point[1]
    val dz = x[3 * vertex + 2] - point[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * The point on the *line* through vertices `a` and `b` that is nearest the origin, written into
 * [into]. A degenerate edge falls back to the endpoint itself rather than dividing by zero.
 */
internal fun edgeTangentPoint(x: DoubleArray, a: Int, b: Int, into: DoubleArray) {
    val ax = x[3 * a]
    val ay = x[3 * a + 1]
    val az = x[3 * a + 2]
    val dx = x[3 * b] - ax
    val dy = x[3 * b + 1] - ay
    val dz = x[3 * b + 2] - az
    val lengthSquared = dx * dx + dy * dy + dz * dz
    val t = if (lengthSquared <= 1e-20) 0.0 else -(ax * dx + ay * dy + az * dz) / lengthSquared
    into[0] = ax + t * dx
    into[1] = ay + t * dy
    into[2] = az + t * dz
}
