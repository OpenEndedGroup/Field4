@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.Vec3
import afield.stdlib.leastSquares
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The middle realization verb: improve an embedding you already have.
 *
 * `realize(policy)` builds coordinates for a topology that has none, [PolyhedralObject.canonicalize]
 * seeks the symmetry-respecting representative, and [PolyhedralObject.relax] — this file — takes an
 * embedding and a declared set of constraints and moves the vertices until the constraints are as
 * satisfied as a local descent can make them. A run is a [RelaxationPlan]: a list of named
 * [RealizationConstraint] values, an [afield.stdlib.LeastSquaresOptions], and an optional
 * [RigidGauge]. The answer is a [RealizationSolveResult] carrying every family's *unweighted*
 * geometric error before and after, so the reader can see which constraint gave way rather than only
 * how small the blended cost got.
 *
 * ```kotlin
 * val result = cube().relax(RelaxationPlan.regularize())
 * result.finalConstraints.forEach { println("${it.name}  rms ${it.rms}  max ${it.max}") }
 * ```
 *
 * ## What is minimised
 *
 * [afield.stdlib.leastSquares] runs Levenberg-Marquardt on half the squared norm of every active
 * family's residuals concatenated. A weight is a plain multiplier on its family's residuals, so it
 * enters the cost as its *square*; and because families contribute wildly different residual counts
 * — three for a centroid, one per face corner for planarity — equal weights are not equal influence.
 * See `Constraints.kt` for the counts.
 *
 * The iteration is local and descent-only. It stops at a stationary point of that one sum and claims
 * nothing about a global minimum, uniqueness, convexity, or the absence of self-intersection;
 * [RealizationSolveResult.converged] means a tolerance was met, not that the answer is the one you
 * wanted. Where the objective itself has a degenerate minimum — the canonical conditions do, because
 * a collapsed edge satisfies [EdgeTangencyConstraint] for free — a converged run can be nonsense, and
 * only the diagnostics will say so.
 *
 * ## Cost per iteration
 *
 * The Jacobian is central finite differences, so an iteration costs `2 * 3 * vertexCount` residual
 * evaluations and builds a dense `3V x 3V` normal matrix. That is the right tool for exact control
 * over tens of vertices. For hundreds use [PolyhedralObject.canonicalize]; to hand the same declared
 * plan to a sparse second-order solver, `CeresRealization.kt` — which needs a native library.
 */

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
 *
 * The constructor rejects an empty list and a list in which every weight is zero, so a plan that
 * cannot ask for anything fails where it was written rather than at solve time.
 */
data class RelaxationPlan(
    /** Residual blocks in list order; a family weighted zero is skipped entirely, not scaled to nothing. */
    val constraints: List<RealizationConstraint>,
    /** Iteration cap and the three convergence tolerances. The Jacobian is finite-difference. */
    val leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 96),
    /** Applied after the solve; null leaves the answer wherever in its null space the minimiser left it. */
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
         * and the tangency centroid at the origin. Solving this is a real alternative to
         * [PolyhedralObject.canonicalize]'s iteration for small objects, and it is how the canonical
         * conditions get *measured* — see [PolyhedralObject.canonicalDiagnostics].
         *
         * There is a fourth family, and it is not one of the conditions: a [MinEdgeLengthConstraint]
         * floor that closes the zero-cost minimum [EdgeTangencyConstraint] documents. It lives here
         * rather than in either solver's options so that both realizers minimise the identical
         * function — the defect is in the objective, not in a solver.
         *
         * @param minEdgeLengthFraction floor under edge length as a fraction of the initial mean.
         *   Zero omits the family, which reproduces the pre-floor objective; on `kkT` that objective
         *   has a minimum with an edge at `0.0007` of the mean and a flattering tangency spread.
         */
        fun canonicalConditions(
            planarityWeight: Double = 2.0,
            tangencyWeight: Double = 1.0,
            tangencyCentroidWeight: Double = 4.0,
            radius: Double = 1.0,
            minEdgeLengthFraction: Double = 0.01,
            leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 128),
        ): RelaxationPlan = RelaxationPlan(
            listOfNotNull(
                FacePlanarityConstraint(planarityWeight),
                EdgeTangencyConstraint(tangencyWeight, radius),
                TangencyCentroidConstraint(tangencyCentroidWeight),
                minEdgeLengthFraction.takeIf { it > 0.0 }?.let { MinEdgeLengthConstraint(fraction = it) },
            ),
            leastSquares,
            RigidGauge(centre = GaugeCentre.EDGE_TANGENCY_CENTROID, scale = GaugeScale.MEAN_EDGE_TANGENCY_RADIUS),
        )
    }
}

/**
 * Root-mean-square of the plan's *weighted* residuals at [x] — the quantity the minimiser is
 * actually driving down, expressed so that any backend can report it the same way rather than
 * whatever its own solver happened to call the cost.
 *
 * Only families with a positive weight contribute, matching what [relax] hands the minimiser. Note
 * that [relax] fills [RealizationSolveResult.rmsResidual] from the Levenberg-Marquardt result itself
 * (same formula, evaluated at the pre-gauge coordinates) while the Ceres path calls this on the
 * gauged coordinates, so the two are the same measure at slightly different points.
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

/**
 * Weights are residual multipliers; set a term to zero to disable that constraint family.
 *
 * The historic five-weight request, kept as a front door onto [RelaxationPlan.regularize] — every
 * weight must be finite and non-negative, and their sum positive. It cannot express the tangency
 * families, a symmetry-orbit term or a gauge, so build a [RelaxationPlan] directly for those.
 */
data class RealizationSolverOptions(
    val edgeLengthWeight: Double = 1.0,
    val facePlanarityWeight: Double = 2.0,
    val centroidWeight: Double = 4.0,
    val radiusWeight: Double = 0.2,
    /** The weak pull back toward the input embedding; small, because it only picks a representative. */
    val referenceWeight: Double = 1e-3,
    /** Null keeps the object's own size by targeting the initial mean edge length. */
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

/**
 * Unconditional geometric measurements of one embedding, in the units of its own coordinates.
 *
 * These know nothing about which constraint families a plan enabled, so a large
 * [edgeLengthRmsDeviation] on a deliberately irregular object is a fact and not a fault. For error
 * measured against what was actually asked for, read [RealizationSolveResult.finalConstraints]; for
 * the canonical conditions specifically, [PolyhedralObject.canonicalDiagnostics].
 */
data class RealizationDiagnostics(
    /** Zero when the topology has no edges, which is also what a perfectly collapsed object reports. */
    val edgeLengthMean: Double,
    /** Rms deviation of edge length about [edgeLengthMean] — an absolute length, not a relative figure. */
    val edgeLengthRmsDeviation: Double,
    /** Worst absolute distance from any face corner to that face's own Newell best-fit plane. */
    val maxFacePlanarityError: Double,
    /** The same distances as an rms over every face corner; a triangle contributes zero by construction. */
    val facePlanarityRmsError: Double,
    /** The vertex centroid, as a fresh mutable [Vec3] the caller owns. */
    val centroid: Vec3,
    /**
     * Faces whose Newell normal is shorter than `1e-10`. That length is twice the polygon's area, so
     * this is an absolute area test and it moves with the object's scale: shrink an embedding far
     * enough and every face is reported degenerate.
     */
    val degenerateFaces: List<ElementId>,
)

/**
 * What one realization run did, and enough evidence to judge it.
 *
 * The pair of constraint reports is the part worth reading. Both cover *every* family in the plan,
 * the zero-weighted ones included, measured unweighted and in the units of the quantity constrained,
 * so the two together say which constraint gave way. Both are measured on coordinates that have been
 * through [Realization3]'s single-precision storage, which puts a floor of roughly `1e-7` relative on
 * anything they can report however good the solve was.
 *
 * [finalConstraints] and [finalDiagnostics] are measured *after* the plan's [RigidGauge] has been
 * applied. That matters for any family with an absolute target: a gauge that rescales the object
 * changes measured edge lengths without the solver having moved anything.
 */
data class RealizationSolveResult(
    /** The gauged result: same topology and attributes, new coordinates. */
    val polyhedron: PolyhedralObject,
    val initialDiagnostics: RealizationDiagnostics,
    /** Measured on [polyhedron], so after the gauge. */
    val finalDiagnostics: RealizationDiagnostics,
    /**
     * One of the minimiser's tolerances was met — not "the answer is right". False means it exhausted
     * its iterations or could not find a downhill step; [reason] says which.
     */
    val converged: Boolean,
    val iterations: Int,
    /**
     * Root-mean-square of the *weighted* residuals: solver units, not a geometric error, and
     * comparable only between runs of the same plan.
     *
     * [relax] reports the minimiser's own final value, at the pre-gauge coordinates;
     * [PolyhedralObject.relaxWithCeres] re-evaluates the same formula on the gauged coordinates. Same
     * quantity, not quite the same point, so do not read a small difference here as a difference in
     * solver quality.
     */
    val rmsResidual: Double,
    /**
     * Why the loop stopped. From [relax], one of `gradient`, `step`, `improvement`, `no accepted step`
     * or `max iterations`; from [PolyhedralObject.relaxWithCeres], a lowercased Ceres termination type.
     */
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
 *
 * The minimum is local. The loop stops on the first of: an infinity-norm gradient below
 * `gradientTolerance`, an accepted step shorter than `stepTolerance`, a cost improvement below
 * `improvementTolerance`, twelve damping increases without an accepted step, or `maxIterations`.
 * [RealizationSolveResult.reason] names which, and only the last two report
 * [RealizationSolveResult.converged] as false.
 *
 * Rejects, before any iteration: a plan whose active weights are all zero, and a plan whose families
 * contribute no residuals at all to this topology. [RealizationProblem.of] additionally requires a
 * realization to already exist and the topology to validate.
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

/**
 * Measure this embedding's edge lengths, face planarity, centroid and degenerate faces, with no plan
 * involved — the "how bad is it, actually" call.
 *
 * Not a free accessor: it throws [IllegalStateException] on an object with no realization and, via
 * [RealizationProblem.of], validates the topology and rejects a non-finite coordinate. Everything is
 * recomputed on each call.
 */
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

/** Inverse of [coordinateVector]. Truncates to single precision, because [Realization3] stores floats. */
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
