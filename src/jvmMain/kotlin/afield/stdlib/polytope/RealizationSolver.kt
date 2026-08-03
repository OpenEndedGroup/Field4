package afield.stdlib.polytope

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.Vec3
import afield.stdlib.leastSquares
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A declared set of realization constraints plus how to solve and how to normalize.
 *
 * The plan is the product distinction the library cares about: a realization run is *data* — a list
 * of named [RealizationConstraint] values a caller can print, diff and reweight — not a fixed
 * residual blend buried in a solver. [gauge] runs after the solve and removes the rigid-motion and
 * scale null spaces explicitly rather than hoping a weak proximity term picked a representative.
 *
 * Cost note: [afield.stdlib.leastSquares] takes a central finite-difference Jacobian, so each
 * iteration costs `2 * 3 * vertexCount` residual evaluations. That is the right tool for exact
 * control on tens of vertices; use [PolyhedralObject.canonicalize] for hundreds.
 */
data class RelaxationPlan(
    val constraints: List<RealizationConstraint>,
    val leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 96),
    val gauge: RigidGauge? = null,
) {
    init {
        require(constraints.isNotEmpty()) { "a relaxation plan needs at least one constraint" }
        require(constraints.any { it.weight > 0.0 }) { "at least one constraint must have a positive weight" }
    }

    /** The same plan with every family of [name] reweighted; unknown names are a no-op. */
    fun withWeight(name: String, weight: Double): RelaxationPlan = copy(
        constraints = constraints.map { if (it.name == name) it.withWeight(weight) else it },
    )

    companion object {
        /**
         * The historic repair/regularization blend: equal edges, planar faces, a pinned centroid, a
         * weak circumsphere and a weak pull toward the input. Useful for symmetric finite polyhedra
         * whose embedding has drifted; wrong for a deliberately irregular object.
         */
        fun regularize(
            edgeLengthWeight: Double = 1.0,
            facePlanarityWeight: Double = 2.0,
            centroidWeight: Double = 4.0,
            radiusWeight: Double = 0.2,
            referenceWeight: Double = 1e-3,
            targetEdgeLength: Double? = null,
            leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 96),
        ): RelaxationPlan = RelaxationPlan(
            listOf(
                EdgeLengthConstraint(edgeLengthWeight, targetEdgeLength),
                FacePlanarityConstraint(facePlanarityWeight),
                CentroidConstraint(centroidWeight),
                CommonRadiusConstraint(radiusWeight),
                ProximityConstraint(referenceWeight),
            ),
            leastSquares,
        )

        /**
         * The canonical form written as constraints: planar faces, one common edge-tangency radius,
         * and the tangency centroid at the origin. Solving this is an honest alternative to
         * [PolyhedralObject.canonicalize]'s iteration for small objects, and it is how the canonical
         * conditions get *measured* — see [PolyhedralObject.canonicalDiagnostics].
         */
        fun canonicalConditions(
            planarityWeight: Double = 2.0,
            tangencyWeight: Double = 1.0,
            tangencyCentroidWeight: Double = 4.0,
            radius: Double = 1.0,
            leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 128),
        ): RelaxationPlan = RelaxationPlan(
            listOf(
                FacePlanarityConstraint(planarityWeight),
                EdgeTangencyConstraint(tangencyWeight, radius),
                TangencyCentroidConstraint(tangencyCentroidWeight),
            ),
            leastSquares,
            RigidGauge(centre = GaugeCentre.EDGE_TANGENCY_CENTROID, scale = GaugeScale.MEAN_EDGE_TANGENCY_RADIUS),
        )
    }
}

/**
 * Root-mean-square of the plan's *weighted* residuals at [x] — the quantity the minimiser is
 * actually driving down, and the one [RealizationSolveResult.rmsResidual] reports.
 *
 * Shared so that every backend reports the same number computed the same way, rather than whatever
 * its own minimiser happened to call the cost.
 */
internal fun RelaxationPlan.weightedResidualRms(problem: RealizationProblem, x: DoubleArray): Double {
    val active = constraints.filter { it.weight > 0.0 }
    var sum = 0.0
    var count = 0
    active.forEach { constraint ->
        val n = constraint.residualCount(problem)
        if (n == 0) return@forEach
        val buffer = DoubleArray(n)
        constraint.residuals(problem, x, buffer, 0)
        buffer.forEach { sum += it * it }
        count += n
    }
    return if (count == 0) 0.0 else sqrt(sum / count)
}

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

    /** The same request expressed in the composable vocabulary. */
    fun toPlan(): RelaxationPlan = RelaxationPlan.regularize(
        edgeLengthWeight,
        facePlanarityWeight,
        centroidWeight,
        radiusWeight,
        referenceWeight,
        targetEdgeLength,
        leastSquares,
    )
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
    /** Unweighted per-family error before the solve, in the units of each constrained quantity. */
    val initialConstraints: List<ConstraintResidualReport> = emptyList(),
    /** The same families after the solve; the pair says which constraint actually gave way. */
    val finalConstraints: List<ConstraintResidualReport> = emptyList(),
)

/**
 * Improve an existing embedding under a declared set of constraints.
 *
 * This is the middle verb of the three the library exposes: `realize(...)` constructs coordinates
 * from a policy, [relax] improves an embedding you already have, and
 * [PolyhedralObject.canonicalize] seeks a symmetry-respecting representative with stated
 * guarantees. [relax] makes no claim about symmetry — it minimises exactly what [plan] asked for
 * and reports every family's remaining error.
 */
fun PolyhedralObject.relax(plan: RelaxationPlan): RealizationSolveResult {
    val problem = RealizationProblem.of(this)
    val active = plan.constraints.filter { it.weight > 0.0 }
    require(active.isNotEmpty()) { "every constraint in the plan has zero weight" }

    val offsets = IntArray(active.size)
    var total = 0
    active.forEachIndexed { at, constraint ->
        offsets[at] = total
        total += constraint.residualCount(problem)
    }
    require(total > 0) { "the plan's constraints contribute no residuals to this topology" }

    val initial = problem.initialCoordinates
    val initialDiagnostics = geometryDiagnostics()
    val initialReports = plan.constraints.report(problem, initial)

    val fit = leastSquares(initial, plan.leastSquares) { x ->
        DoubleArray(total).also { buffer ->
            active.forEachIndexed { at, constraint -> constraint.residuals(problem, x, buffer, offsets[at]) }
        }
    }

    val solvedRaw = Realization3(
        (0 until problem.vertexCount).map { i ->
            Vec3(fit.x[3 * i].toFloat(), fit.x[3 * i + 1].toFloat(), fit.x[3 * i + 2].toFloat())
        },
    )
    val gauged = plan.gauge?.let { copy(realization = solvedRaw).normalize(it, reference = realization) }
        ?: copy(realization = solvedRaw)

    return RealizationSolveResult(
        gauged,
        initialDiagnostics,
        gauged.geometryDiagnostics(),
        fit.converged,
        fit.iterations,
        fit.rms,
        fit.reason,
        initialReports,
        plan.constraints.report(problem, gauged.coordinateVector()),
    )
}

/**
 * Improve a supplied embedding without changing its authoritative topology or attributes.
 *
 * This is a general constraint relaxation, not a claim of a unique Conway "canonical form". The
 * defaults are useful for the symmetric finite polyhedra produced here. Disable common-radius or
 * common-edge constraints for deliberately irregular objects, or call [relax] with an explicit
 * [RelaxationPlan] to say exactly which constraint families apply.
 */
fun PolyhedralObject.solveRealization(
    options: RealizationSolverOptions = RealizationSolverOptions(),
): RealizationSolveResult = relax(options.toPlan())

fun PolyhedralObject.geometryDiagnostics(): RealizationDiagnostics {
    realization ?: throw IllegalStateException("geometry diagnostics require a 3D realization")
    val problem = RealizationProblem.of(this)
    val x = problem.initialCoordinates
    val edgeLengths = (0 until problem.edgeCount).map { distanceBetween(x, problem.edgeA(it), problem.edgeB(it)) }
    val edgeMean = if (edgeLengths.isEmpty()) 0.0 else edgeLengths.average()
    val edgeRms = sqrt(edgeLengths.sumOf { (it - edgeMean) * (it - edgeMean) } / max(1, edgeLengths.size))
    val planarity = mutableListOf<Double>()
    val degenerate = mutableListOf<ElementId>()
    val faceElements = topology.elements(2)
    problem.faceCycles.forEachIndexed { at, cycle ->
        val plane = facePlane(x, cycle)
        if (plane.normalLength <= 1e-10) degenerate += faceElements[at].id
        cycle.forEach { planarity += kotlin.math.abs(signedPlaneDistance(x, it, plane)) }
    }
    return RealizationDiagnostics(
        edgeMean,
        edgeRms,
        planarity.maxOrNull() ?: 0.0,
        sqrt(planarity.sumOf { it * it } / max(1, planarity.size)),
        centroidOf(x).let { Vec3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) },
        degenerate,
    )
}

/** The realization packed as `x[3 * vertex + axis]`, in `topology.elements(0)` order. */
internal fun PolyhedralObject.coordinateVector(): DoubleArray {
    val realization = realization ?: throw IllegalStateException("this polyhedron has no 3D realization")
    return DoubleArray(realization.size * 3) { i ->
        when (i % 3) {
            0 -> realization.x(i / 3).toDouble()
            1 -> realization.y(i / 3).toDouble()
            else -> realization.z(i / 3).toDouble()
        }
    }
}

internal fun PolyhedralObject.withCoordinateVector(x: DoubleArray): PolyhedralObject {
    require(x.size == topology.elements(0).size * 3) {
        "coordinate vector has ${x.size} entries for ${topology.elements(0).size} vertices"
    }
    return copy(
        realization = Realization3(
            (0 until x.size / 3).map { i -> Vec3(x[3 * i].toFloat(), x[3 * i + 1].toFloat(), x[3 * i + 2].toFloat()) },
        ),
    )
}
