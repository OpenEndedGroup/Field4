@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The constraint vocabulary a realization run is written in.
 *
 * A [RealizationConstraint] is one named, immutable family of residuals — "every edge the same
 * length", "every face planar", "the tangency centroid at the origin". A [RelaxationPlan] is a list
 * of them, so a realization run is *data*: it prints, it diffs, it reweights with
 * [RelaxationPlan.withWeight], and it can be handed to a second minimiser and the answers compared.
 * [RealizationProblem] is the boundary that makes that cheap: it derives the edge endpoints, the face
 * cycles and the initial coordinates once per solve, and hands a constraint nothing but flat
 * `DoubleArray` coordinates and integer indices — no [ElementId] bookkeeping, no FLines, no renderer.
 *
 * ## Units, and what a weight multiplies
 *
 * Every residual in this file is a **length in the units of the coordinates**, and every one is
 * written as `weight * (measured - goal)`. So the families are directly comparable to each other, and
 * a weight is a plain multiplier that enters the least-squares cost as its square.
 *
 * What a weight is *not* is a share of influence, because the families contribute very different
 * numbers of residuals for the same object:
 *
 * - [EdgeLengthConstraint] and [EdgeTangencyConstraint] — one per edge.
 * - [CommonRadiusConstraint] — one per vertex.
 * - [FacePlanarityConstraint] — one per face **corner**, so it is usually the largest block.
 * - [ProximityConstraint] — three per vertex.
 * - [CentroidConstraint] and [TangencyCentroidConstraint] — three, whatever the object's size.
 *
 * On a cube: 12 edge-length residuals, 8 common-radius, 24 face-planarity, 24 proximity, and 3 for
 * either centring family. The default blends in [RelaxationPlan.regularize] and
 * [RelaxationPlan.canonicalConditions] give the three-residual centring families a much larger weight
 * for exactly this reason; read a weight as calibration against a residual count, not as a priority.
 *
 * ## Reporting
 *
 * [RealizationConstraint.residualCount] may not depend on the coordinates, which is what makes a run's
 * residual vector sliceable per family after the fact. [report] goes one step further and re-evaluates
 * each family at weight 1, so a [RealizationSolveResult] can state each family's error in the units of
 * the thing constrained rather than in solver units.
 *
 * ## Writing your own
 *
 * Implement the interface: it is four small members and nothing in this file is privileged. Two rules
 * that are not obvious — write exactly [RealizationConstraint.residualCount] entries starting at the
 * offset you are given, and make `withWeight(1.0)` genuinely unweighted, since that is what [report]
 * relies on. A custom family has no Ceres equivalent, so a plan containing one cannot cross over to
 * [PolyhedralObject.relaxWithCeres]; [CeresRealization.unsupported] names it.
 */

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
    /** The object the indices were derived from. Its own coordinates are a starting point, not the ones being solved. */
    val polyhedron: PolyhedralObject,
    internal val edgeEndpoints: IntArray,
    internal val faceCycles: List<IntArray>,
    internal val initial: DoubleArray,
) {
    val vertexCount: Int get() = initial.size / 3
    val edgeCount: Int get() = edgeEndpoints.size / 2

    /** Rank-2 elements, all of which are guaranteed to have an ordered boundary here. */
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
         *
         * The strictness is deliberate — this is the gate every solver call goes through, so it is
         * where a malformed object is caught rather than silently realized. It throws for: a missing
         * realization, a topology that fails [PolytopeTopology.validate], no vertices at all, a
         * non-finite initial coordinate, and an edge without exactly two endpoints.
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

/**
 * Every edge the same length. [target] defaults to the initial embedding's mean edge length.
 *
 * One residual per edge, `length - target`, in coordinate units. Defaulting the target to the current
 * mean is what makes this family constrain *equality* rather than size: it will happily leave the
 * object at whatever scale it arrived at, and equalise within that.
 */
data class EdgeLengthConstraint(
    override val weight: Double = 1.0,
    /** An absolute length. Rejected at construction unless positive; null means the initial mean. */
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

/**
 * Every face corner on that face's own best-fit plane: one residual per face corner.
 *
 * The plane is not a free variable — it is the Newell area-weighted plane recomputed from the current
 * coordinates on every evaluation, so one corner's residual depends on every other corner of that
 * face, and a triangle's residuals are identically zero. The residual is a signed distance in
 * coordinate units.
 *
 * This is the largest residual block on most objects (see the file overview), and the one place where
 * the Ceres realizer solves a genuinely different problem: [PlaneFormulation] explains the three ways
 * a plane can be parameterised there and which one matches this constraint exactly.
 */
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
 * rather than moving it; use [AT_ORIGIN] to also recentre it.
 *
 * Three residuals, one per axis, in coordinate units — the whole family is three numbers however
 * large the object, which is why the stock plans weight it well above the per-element families. Its
 * job is to remove the translation null space, not to shape anything.
 */
data class CentroidConstraint(
    override val weight: Double = 4.0,
    /** Three components exactly, or null for the initial centroid. */
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
        /** Move the vertex centroid to the origin rather than merely holding it where it was. */
        val AT_ORIGIN = CentroidConstraint(target = listOf(0.0, 0.0, 0.0))
    }
}

/**
 * Every vertex the same distance from the current vertex centroid — a circumsphere.
 *
 * This is the right constraint for the Platonic and Archimedean families and exactly the wrong one
 * for a deliberately irregular object, which is why it is a value the caller opts into.
 *
 * One residual per vertex, `distance - target`, in coordinate units. The centre is the *current*
 * vertex centroid, recomputed each evaluation, so the family is translation-invariant and does not
 * fight [CentroidConstraint]. It does conflict with equal edge lengths on anything but a
 * vertex-transitive solid, which is why [RelaxationPlan.regularize] gives it a weight of 0.2 against
 * edge length's 1.0 — a nudge toward a circumsphere, not an instruction.
 */
data class CommonRadiusConstraint(
    override val weight: Double = 0.2,
    /** An absolute radius. Rejected at construction unless positive; null means the initial mean. */
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
 *
 * Three residuals per vertex — per coordinate, not per distance — each `x - initialX`, so this is the
 * one family that is *not* invariant under rigid motion, which is exactly what makes it able to pin
 * one down. It is also the bluntest instrument here: at any appreciable weight it will hold a vertex
 * away from where the other families want it and cap how well they can be satisfied, with nothing to say so. A
 * [RigidGauge] on the plan does the same job afterwards without biasing the objective.
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
 * The origin is not the vertex centroid: the canonical form centres on the *tangency*
 * points, and mixing the two centres is the classic way to make this constraint fight itself. Pair
 * it with [TangencyCentroidConstraint].
 *
 * One residual per edge, in coordinate units. It carries a defect that bites a converged run: the
 * measurement is to the nearest point of the infinite *line* through the two endpoints, and
 * as an edge collapses that line's direction becomes arbitrary, so a collapsed edge can place its
 * tangency point anywhere on the sphere and satisfies this constraint for free. Collapsing an edge
 * therefore *removes* a constraint, and the canonical objective has exact zero-cost minima in which
 * part of the solid has folded up. [MinEdgeLengthConstraint] is the guard, and
 * [RelaxationPlan.canonicalConditions] includes one by default — for both realizers, since the defect
 * is in the objective rather than in either solver.
 */
data class EdgeTangencyConstraint(
    override val weight: Double = 1.0,
    /**
     * An absolute midsphere radius, or null for the initial mean — which constrains the *spread* of
     * the tangency radii and leaves their common value alone.
     */
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

/**
 * A floor under edge length: one one-sided residual per edge, `max(0, floor - length)`.
 *
 * This exists for one reason, and it is not shape control — it closes a hole in
 * [EdgeTangencyConstraint]. That family measures the distance from the origin to the *line* through an
 * edge, so as an edge shrinks its direction becomes arbitrary and it satisfies its own constraint for
 * nothing; collapsing edges then *removes* constraints and the canonical objective grows exact
 * zero-cost minima with part of the solid folded up. `kkC` is the case where that stops being
 * theoretical: without a floor, `canonicalize()` walks away from its own starting embedding and hands
 * back a sliver.
 *
 * One-sided is the whole point. At or above the floor every residual is exactly `0.0` — not small,
 * zero — so this is inert on a healthy solid and cannot pull a shape it was not asked to pull. What it
 * cannot be is *differentiable* at the floor: `max` has a kink there, and a finite-difference Jacobian
 * sees a one-sided slope. That is the right trade for a barrier and the wrong one anywhere else.
 *
 * [fraction] is of the initial mean edge length rather than absolute, so the floor means the same thing
 * whatever scale the caller's polyhedron arrived at.
 */
data class MinEdgeLengthConstraint(
    override val weight: Double = 1.0,
    /** Floor as a fraction of the initial mean edge length. */
    val fraction: Double = 0.01,
) : RealizationConstraint {
    override val name get() = "min-edge-length"

    init {
        require(weight.isFinite() && weight >= 0.0) { "min-edge-length weight must be finite and non-negative" }
        require(fraction.isFinite() && fraction >= 0.0) { "min-edge-length fraction must be finite and non-negative" }
        require(fraction < 1.0) { "a floor at $fraction of the mean edge length would bind on a healthy solid" }
    }

    override fun residualCount(problem: RealizationProblem) = problem.edgeCount

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        val floor = fraction * problem.initialMeanEdgeLength
        for (edge in 0 until problem.edgeCount) {
            val a = problem.edgeA(edge)
            val b = problem.edgeB(edge)
            val dx = x[3 * b] - x[3 * a]
            val dy = x[3 * b + 1] - x[3 * a + 1]
            val dz = x[3 * b + 2] - x[3 * a + 2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            into[offset + edge] = weight * max(0.0, floor - length)
        }
    }

    override fun withWeight(weight: Double) = copy(weight = weight)
}

/**
 * The centroid of every edge's tangency point at the origin: the canonical form's centring.
 *
 * Three residuals in coordinate units, and not the same thing as [CentroidConstraint.AT_ORIGIN].
 * Tangency points are defined relative to the origin, so they move when the object does and one
 * translation does not put their centroid there — this family asks the *solver* to find that fixed
 * point, and [GaugeCentre.EDGE_TANGENCY_CENTROID] iterates to it afterwards.
 */
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

/**
 * Unweighted magnitudes of one constraint family at one embedding.
 *
 * The pair worth reading is [rms] and [max]: an rms near zero with a large max is one bad face or one
 * bad edge, which is a different problem from a uniformly mediocre fit.
 */
data class ConstraintResidualReport(
    val name: String,
    /** The weight the family *declared* — the errors below are measured with it set to 1. */
    val weight: Double,
    /** Residuals in this family. Zero means the family had nothing to say about this topology. */
    val count: Int,
    /** Root-mean-square error over the family, in the units of the quantity it constrains. */
    val rms: Double,
    /** Largest absolute residual in the family; zero for an empty family. */
    val max: Double,
)

/**
 * Evaluate every family at [x] and report its *unweighted* geometric error, so a reader sees the
 * residual in the units of the thing being constrained rather than in solver units.
 *
 * Covers the whole list including families weighted zero — a plan is a declaration, and a family
 * excluded from the objective is still worth measuring. Each family is re-evaluated at weight 1, so
 * this costs one extra residual sweep per family and is not something to call inside a loop.
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

/** A face's centroid and its *unnormalised* Newell normal; [normalLength] is twice the polygon area. */
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

/**
 * Distance from a vertex to [plane], signed along the normal.
 *
 * The divisor is the Newell normal's length clamped at `1e-10`, so a degenerate face yields a small
 * bounded number rather than a NaN — bounded by the corner's distance from the face centroid, and
 * meaningless as a plane distance. Check [RealizationDiagnostics.degenerateFaces] before believing a
 * planarity figure.
 */
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
