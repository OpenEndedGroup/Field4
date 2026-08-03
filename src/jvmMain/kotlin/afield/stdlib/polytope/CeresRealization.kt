package afield.stdlib.polytope

import afield.stdlib.Vec3
import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresFeature
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.CeresProgram
import afield.stdlib.ceres.CeresSolveSummary
import afield.stdlib.ceres.CeresSolverBackend
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.buildCeresProgram
import afield.stdlib.ceres.Vec3 as CeresVec3

/**
 * The same [RelaxationPlan] the Kotlin solver runs, handed to Ceres instead.
 *
 * [relax] takes a central finite-difference Jacobian and builds a dense `3V x 3V` normal matrix
 * every iteration, which is `2 * 3V` residual evaluations plus an `O(m * (3V)^2)` accumulation. That
 * is the right tool for exact control on tens of vertices and it is the documented reason
 * [PolyhedralObject.canonicalize] exists for hundreds. Ceres differentiates each residual
 * analytically (forward-mode autodiff over only the parameters that residual touches) and can factor
 * the sparse normal equations, so the per-iteration cost tracks the number of *incidences* rather
 * than the square of the vertex count.
 *
 * This is deliberately a second implementation of the same declared objective, not a replacement:
 * [relaxWithCeres] returns the same [RealizationSolveResult] as [relax] and applies the same
 * [RigidGauge] afterwards, so the two can be run on the same plan and diffed. Where they disagree,
 * see "Where the two solvers genuinely differ" below — the differences are real, not rounding.
 *
 * ## Availability
 *
 * Needs the ABI v2 native wrapper (`./gradlew buildCeresNative`, then run with
 * `-Dafield.ceres.library=build/ken-ceres/libken_ceres_jvm.dylib`). [ceresRealizationAvailable]
 * answers whether this JVM has one; [relaxWithCeres] throws a description of what is missing rather
 * than falling back silently, because a silent fallback would make a performance comparison lie.
 *
 * ## Where the two solvers genuinely differ
 *
 * - **Face planarity.** [FacePlanarityConstraint] measures each corner against a Newell best-fit
 *   plane recomputed from the current coordinates, so the residual for one corner depends on every
 *   corner of that face. The Ceres `PLANAR` opcode instead gives each face its own plane — an origin
 *   and a unit normal on a `SphereManifold` — as free variables. The minima agree (the best-fit
 *   plane is what minimising over a free plane finds) but the paths differ, and Ceres' version adds
 *   two very weak stabilisers (`1e-6`) holding each plane near its seed.
 * - **Weights.** A [RealizationConstraint] weight multiplies the residual. The native wrapper takes
 *   a weight and multiplies the residual by its *square root*. The mapping below squares every
 *   weight to compensate; that convention is easy to get wrong by hand.
 * - **Loss.** The wrapper defaults to `Huber(1e-3)` on every residual, which would cap the
 *   influence of whichever constraint is currently worst and quietly change the objective. These
 *   programs are built with [RobustLoss.NONE].
 */
object CeresRealization {
    /** Families with no Ceres equivalent yet; a plan containing one cannot cross over. */
    fun unsupported(plan: RelaxationPlan): List<String> =
        plan.constraints.filter { it.weight > 0.0 }.mapNotNull { constraint ->
            when (constraint) {
                is EdgeLengthConstraint,
                is FacePlanarityConstraint,
                is CentroidConstraint,
                is CommonRadiusConstraint,
                is ProximityConstraint,
                is EdgeTangencyConstraint,
                is TangencyCentroidConstraint,
                -> null

                else -> constraint.name
            }
        }

    /**
     * Compile [plan] against [problem]'s topology.
     *
     * Every default resolved from the initial embedding — an edge-length target, a centroid target,
     * a tangency radius — is resolved here, exactly as the Kotlin constraint would resolve it, so
     * the two solvers are given the same numbers and not merely the same intent.
     */
    fun program(
        plan: RelaxationPlan,
        problem: RealizationProblem,
        planes: PlaneFormulation = PlaneFormulation.HESSE,
        gaugeRotationWeight: Double = 0.0,
        minEdgeLengthFraction: Double = 0.0,
        minEdgeLengthWeight: Double = 1.0,
    ): CeresProgram {
        val missing = unsupported(plan)
        require(missing.isEmpty()) { "no Ceres equivalent for constraint families: $missing" }

        val edges = (0 until problem.edgeCount).map { problem.edgeA(it) to problem.edgeB(it) }
        val faces = problem.faceCycles.map { it.toList() }

        // A Kotlin residual is `weight * (quantity - goal)`; the native functors multiply by
        // sqrt(weight). Square it here so the two objectives are the same function.
        fun Double.asCeresWeight() = this * this

        return buildCeresProgram(RobustLoss.NONE) {
            plan.constraints.filter { it.weight > 0.0 }.forEach { constraint ->
                val w = constraint.weight.asCeresWeight()
                when (constraint) {
                    is EdgeLengthConstraint -> {
                        val goal = constraint.target ?: problem.initialMeanEdgeLength
                        edges.forEach { (a, b) -> distance(a, b, goal, weight = w) }
                    }

                    is FacePlanarityConstraint -> faces.forEach { cycle ->
                        when (planes) {
                            PlaneFormulation.ORIGIN_NORMAL -> planar(*cycle.toIntArray(), weight = w)
                            PlaneFormulation.HESSE -> planarHesse(cycle, weight = w)
                            PlaneFormulation.NEWELL_FIT -> planarFit(cycle, weight = w)
                        }
                    }

                    is CentroidConstraint -> {
                        val goal = constraint.target ?: problem.initialCentroid().toList()
                        centroidAll(CeresVec3(goal[0], goal[1], goal[2]), weight = w)
                    }

                    is CommonRadiusConstraint ->
                        radiusAboutCentroidAll(
                            constraint.target ?: problem.initialMeanVertexRadius,
                            weight = w,
                        )

                    // PRIOR_3 targets are refreshed to whatever pose is passed in at solve time,
                    // and the pose we pass is the initial embedding — which is exactly what
                    // ProximityConstraint measures against.
                    is ProximityConstraint -> weakAll(weight = w)

                    is EdgeTangencyConstraint -> {
                        val radius = constraint.radius ?: problem.initialMeanTangencyRadius
                        edges.forEach { (a, b) -> edgeTangency(a, b, radius, weight = w) }
                    }

                    is TangencyCentroidConstraint -> tangencyCentroid(edges, weight = w)

                    else -> error("unreachable: unsupported() should have rejected ${constraint.name}")
                }
            }
            if (gaugeRotationWeight > 0.0) {
                gaugeRotationAll(weight = gaugeRotationWeight * gaugeRotationWeight)
            }
            if (minEdgeLengthFraction > 0.0) {
                // A fraction of the initial mean edge length, so the barrier means the same thing
                // whatever scale the caller's polyhedron happens to be at.
                val floor = minEdgeLengthFraction * problem.initialMeanEdgeLength
                val w = minEdgeLengthWeight * minEdgeLengthWeight
                if (floor > 0.0) edges.forEach { (a, b) -> minDistance(a, b, floor, weight = w) }
            }
        }
    }
}

/**
 * How a Ceres program should say "this face is planar". They minimise the same thing; they are not
 * equally well conditioned.
 */
enum class PlaneFormulation {
    /**
     * The original `PLANAR` opcode: plane as origin plus normal. Carries an exact two-dimensional
     * null space per face (the residual sees the origin only through `O . n`) restrained by a
     * `1e-6` prior. Kept because it is what the architectural DSL has always used; not a good
     * choice for realization.
     */
    ORIGIN_NORMAL,

    /** Plane as unit normal plus scalar offset. Three effective parameters per face, no null space. */
    HESSE,

    /**
     * No plane variables at all — the Newell best-fit plane is computed inside the residual, which
     * is exactly what [FacePlanarityConstraint] does. The smallest problem, and the only choice
     * under which the two realizers minimise the identical function.
     */
    NEWELL_FIT,
}

/** Solver knobs for [relaxWithCeres] that have no counterpart in [LeastSquaresOptions]. */
data class CeresRelaxationOptions(
    val maxIterations: Int = 200,
    /**
     * [CeresLinearSolver.SPARSE_NORMAL_CHOLESKY] is the right default here — a realization problem
     * is structurally sparse — but it needs a Ceres built with a sparse backend, and Ceres silently
     * falls back to a dense one when there is none. [CeresRelaxationResult.summary] reports what was
     * actually used.
     */
    val linearSolver: CeresLinearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
    val threads: Int = 1,
    val functionTolerance: Double = 1e-10,
    val gradientTolerance: Double = 1e-14,
    val parameterTolerance: Double = 1e-12,
    /** See [PlaneFormulation]. The default is the well-conditioned one, not the historic one. */
    val planes: PlaneFormulation = PlaneFormulation.HESSE,
    /**
     * Weight on a [afield.stdlib.ceres.GaugeRotation] term, or zero for none.
     *
     * The canonical conditions pin translation and scale but not orientation, leaving a
     * three-dimensional continuum of equally good answers for the minimiser to wander along. The
     * plan's [RigidGauge] removes that freedom *after* the solve; this removes it during.
     */
    val gaugeRotationWeight: Double = 1.0,
    /**
     * Let the trust region accept an occasional worse step. Helps on the spiky kis-family solids
     * whose canonical form sits past a ridge; costs nothing when the problem is already easy.
     */
    val nonmonotonicSteps: Boolean = false,
    /**
     * A floor on edge length, as a one-sided `max(0, floor - length)` barrier. Zero disables it.
     *
     * Not cosmetic. [EdgeTangencyConstraint] measures the distance from the origin to the *line*
     * through an edge's endpoints, and as those endpoints converge that line's direction becomes
     * arbitrary — so a collapsed edge can put its tangency point anywhere on the sphere and
     * satisfies the constraint for free. Collapsing edges therefore *removes* constraints, and the
     * canonical objective has a spurious zero-cost minimum in which a chunk of the solid has folded
     * up. On the spiky kis-family solids Ceres finds it: `kkT` reaches cost 5.7e-17 with 12 of its
     * 54 edges at length 1e-7.
     *
     * The floor is this fraction of the initial mean edge length, so it means the same thing at any
     * scale. `max(0, floor - length)` contributes exactly nothing while every edge is longer than
     * the floor — turning it on is bit-for-bit inert on `kkC`, `kkD`, `kC` and `tI` — and it rescues
     * `kkO` and `kkI` from tangency spread 3e-1 to 2e-4. Larger values start to bias rather than
     * merely guard, so the default is small.
     *
     * [relax] has no equivalent and shares the underlying defect; it is only saved from it by
     * converging too slowly to reach the trap.
     */
    val minEdgeLengthFraction: Double = 0.01,
)

/** [RealizationSolveResult] plus what Ceres reported about the solve that produced it. */
data class CeresRelaxationResult(
    val realization: RealizationSolveResult,
    val summary: CeresSolveSummary,
)

/** True when this JVM has a native wrapper new enough to run [relaxWithCeres]. */
fun ceresRealizationAvailable(): Boolean = runCatching {
    CeresFeature.AbiV2 in FfmCeresBackend().supportedFeatures
}.getOrDefault(false)

/**
 * [relax], with Ceres doing the minimisation.
 *
 * Same plan in, same [RealizationSolveResult] out, same [RigidGauge] applied afterwards — so a
 * caller can swap one for the other, or run both and compare the reported per-family errors.
 */
fun PolyhedralObject.relaxWithCeres(
    plan: RelaxationPlan,
    options: CeresRelaxationOptions = CeresRelaxationOptions(),
    backend: CeresSolverBackend = FfmCeresBackend(),
): CeresRelaxationResult {
    require(CeresFeature.AbiV2 in backend.supportedFeatures) {
        "the Ceres realizer needs the ABI v2 native wrapper; build it with ./gradlew buildCeresNative " +
            "and point -Dafield.ceres.library at build/ken-ceres/libken_ceres_jvm.dylib"
    }

    val problem = RealizationProblem.of(this)
    val active = plan.constraints.filter { it.weight > 0.0 }
    require(active.isNotEmpty()) { "every constraint in the plan has zero weight" }

    val initial = problem.initialCoordinates
    val initialDiagnostics = geometryDiagnostics()
    val initialReports = plan.constraints.report(problem, initial)

    val program = CeresRealization.program(
        plan,
        problem,
        options.planes,
        options.gaugeRotationWeight,
        options.minEdgeLengthFraction,
    )
    val result = CachedCeresSolvers(backend).use { solvers ->
        solvers.solve(
            SolveRequest(
                PointCloud(initial),
                program,
                options = SolveOptions(
                    maxIterations = options.maxIterations,
                    linearSolver = options.linearSolver,
                    threads = options.threads,
                    functionTolerance = options.functionTolerance,
                    gradientTolerance = options.gradientTolerance,
                    parameterTolerance = options.parameterTolerance,
                    nonmonotonicSteps = options.nonmonotonicSteps,
                    summary = true,
                ),
            ),
        )
    }
    val summary = requireNotNull(result.summary) { "the v2 backend did not return a summary" }

    val solvedRaw = Realization3(
        (0 until problem.vertexCount).map { i ->
            Vec3(
                result.points.values[3 * i].toFloat(),
                result.points.values[3 * i + 1].toFloat(),
                result.points.values[3 * i + 2].toFloat(),
            )
        },
    )
    val gauged = plan.gauge?.let { copy(realization = solvedRaw).normalize(it, reference = realization) }
        ?: copy(realization = solvedRaw)

    return CeresRelaxationResult(
        RealizationSolveResult(
            gauged,
            initialDiagnostics,
            gauged.geometryDiagnostics(),
            converged = summary.converged,
            iterations = summary.iterations,
            // Deliberately NOT sqrt(2 * summary.finalCost / residualCount). Ceres' cost also counts
            // residuals the caller never wrote: the PLANAR opcode gives each face a free plane and
            // adds two weak (1e-6) stabilisers holding it near its seed, and on a 32-face solid
            // those dominate the final cost once the real residuals reach 1e-8. Reporting that
            // number would make this solver look worse than it is, and would not be comparable with
            // what [relax] reports. Re-evaluate the plan's own residuals instead.
            rmsResidual = plan.weightedResidualRms(problem, gauged.coordinateVector()),
            reason = summary.termination.name.lowercase(),
            initialConstraints = initialReports,
            finalConstraints = plan.constraints.report(problem, gauged.coordinateVector()),
        ),
        summary,
    )
}
