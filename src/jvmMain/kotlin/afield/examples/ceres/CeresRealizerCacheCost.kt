package afield.examples.ceres

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.Vec3
import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.polytope.CeresRealization
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.Realization3
import afield.stdlib.polytope.RealizationProblem
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.ceresRealizationAvailable
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.geometryDiagnostics
import afield.stdlib.polytope.normalize
import afield.stdlib.polytope.relaxWithCeres
import afield.stdlib.polytope.report
import afield.stdlib.polytope.weightedResidualRms
import kotlin.math.sin

/**
 * Where the time in [relaxWithCeres] actually goes when the same topology is relaxed repeatedly.
 *
 * [relaxWithCeres] creates and closes its [CachedCeresSolvers] inside the call, so it compiles a fresh
 * native program every time and nothing is amortised. That is either a serious waste in a loop or a
 * rounding error against the solve itself, and reading the code cannot say which: it depends on how
 * native program compilation compares with a Levenberg-Marquardt solve and with the Kotlin-side plan
 * bookkeeping the call does either way.
 *
 * So this prints three things, on the plan and seeds the realizer would actually use:
 *
 * 1. A call broken into its parts, and what share of it compiling the program is.
 * 2. The same program solved [REPS] times with a fresh cache per solve and with one shared — the
 *    change actually made rather than subtracted, and an upper bound on what it is worth.
 * 3. How many *distinct* programs [REPS] frames of a moving solid produce, which is what decides
 *    whether a cache in an animation loop can hit at all.
 *
 * The conclusion is written up on [relaxWithCeres]; the summary is that compilation is 4–7% of a call
 * on a small solid, 0.1–0.3% on a large one, and 0 hits out of 20 on anything that moves.
 */
private data class CacheCase(val expression: String, val label: String)

private val CACHE_CASES = listOf(
    CacheCase("C", "cube"),
    CacheCase("kC", "kis cube"),
    CacheCase("tI", "truncated icosahedron"),
    CacheCase("kkC", "kis kis cube"),
    CacheCase("kkkC", "kis^3 cube"),
    CacheCase("kkkkC", "kis^4 cube"),
)

/** The length of the loop a shared cache is supposed to serve, and the timing rep count. */
private const val REPS = 20

/** Best-of-[reps] after [warmup] discarded runs — the stable statistic for a deterministic computation. */
private fun <T> best(warmup: Int = 3, reps: Int = REPS, body: () -> T): Pair<T, Long> {
    repeat(warmup) { body() }
    var top = Long.MAX_VALUE
    var last: T? = null
    repeat(reps) {
        val start = System.nanoTime()
        last = body()
        top = minOf(top, System.nanoTime() - start)
    }
    @Suppress("UNCHECKED_CAST")
    return (last as T) to top
}

fun main() {
    check(ceresRealizationAvailable()) {
        "needs the ABI v2 wrapper: ./gradlew buildCeresNative, then run with " +
            "-PceresLibrary=build/ken-ceres/libken_ceres_jvm.dylib"
    }

    val options = CeresRelaxationOptions(
        maxIterations = 200,
        linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
    )
    val plan = RelaxationPlan.canonicalConditions(leastSquares = LeastSquaresOptions(maxIterations = 200))
    val solveOptions = SolveOptions(
        maxIterations = options.maxIterations,
        linearSolver = options.linearSolver,
        threads = options.threads,
        functionTolerance = options.functionTolerance,
        gradientTolerance = options.gradientTolerance,
        parameterTolerance = options.parameterTolerance,
        nonmonotonicSteps = options.nonmonotonicSteps,
        summary = true,
    )

    println("Where one relaxWithCeres call goes (best of $REPS):")
    println(
        "%-5s %5s %5s   %9s %9s %9s %9s %9s %9s %9s %9s   %s".format(
            "solid", "V", "F", "call", "program", "hash", "backend", "compile", "solve", "report",
            "gauge", "compile share",
        ),
    )

    for (case in CACHE_CASES) {
        val seed: PolyhedralObject = conway(case.expression).polyhedron
        val problem = RealizationProblem.of(seed)
        val cloud = PointCloud(problem.initialCoordinates)
        val backend = FfmCeresBackend()

        // The whole call, as a caller writes it today: build the program, compile it, solve, close.
        val (_, callNanos) = best { seed.relaxWithCeres(plan, options) }

        // The Kotlin half of that: turning the plan into a CeresProgram, defaults resolved from the
        // initial embedding. A hoisted cache does not remove this — only a caller who holds the
        // program does.
        val (program, programNanos) = best {
            CeresRealization.program(
                plan,
                problem,
                options.planes,
                options.gaugeRotationWeight,
                options.minEdgeLengthFraction,
            )
        }

        // What CachedCeresSolvers pays on every solve, hit or miss: the cache key is the program's
        // legacy-bytecode hash, and CeresProgram re-encodes itself to compute it. Hoisting the cache
        // does not remove this; only holding the compiled solver does.
        val (_, hashNanos) = best { program.legacyHash() }

        // relaxWithCeres' `backend` default argument, which is evaluated per call.
        val (_, backendNanos) = best { FfmCeresBackend() }

        // What a cache hit removes: handing the program to the native wrapper.
        val (_, compileNanos) = best { backend.compile(cloud, program).close() }

        // What is left however the caching is arranged.
        val solver = backend.compile(cloud, program)
        val (_, solveNanos) = best { solver.solve(cloud, options = solveOptions) }
        solver.close()

        // The rest of what a call does either way: the problem, before-and-after diagnostics, the
        // per-family residual reports, and the residual RMS re-evaluated on the plan's own residuals.
        val coordinates = problem.initialCoordinates
        val (_, reportNanos) = best {
            RealizationProblem.of(seed)
            seed.geometryDiagnostics()
            seed.geometryDiagnostics()
            plan.constraints.report(problem, coordinates)
            plan.constraints.report(problem, coordinates)
            plan.weightedResidualRms(problem, coordinates)
        }

        // And the RigidGauge the result is normalised into, which both realizers apply after the solve.
        val gauge = requireNotNull(plan.gauge) { "the canonical plan is expected to carry a gauge" }
        val (_, gaugeNanos) = best { seed.normalize(gauge, reference = seed.realization) }

        println(
            "%-5s %5d %5d   %7.2fms %7.2fms %7.2fms %7.2fms %7.2fms %7.2fms %7.2fms %7.2fms   %4.1f%%  (%s)".format(
                case.expression,
                problem.vertexCount,
                seed.topology.elements(2).size,
                callNanos / 1e6,
                programNanos / 1e6,
                hashNanos / 1e6,
                backendNanos / 1e6,
                compileNanos / 1e6,
                solveNanos / 1e6,
                reportNanos / 1e6,
                gaugeNanos / 1e6,
                100.0 * compileNanos / callNanos,
                case.label,
            ),
        )
    }

    println(
        "\n'compile share' is compile / call: roughly what a hoisted CachedCeresSolvers would remove from\n" +
            "a repeated relaxWithCeres on one topology. Read it as a floor rather than a bound — compiling\n" +
            "and closing in a tight loop is the friendly case for the native allocator, and the next table\n" +
            "measures the change directly instead. 'program' is the Kotlin-side plan mapping and 'hash' the\n" +
            "cache key CachedCeresSolvers recomputes on every solve, hit or miss; only a caller holding the\n" +
            "compiled solver escapes those. 'solve', 'report' and 'gauge' — the problem, the two sets of\n" +
            "diagnostics, the two residual reports and the RigidGauge — are paid however caching is done.",
    )

    // The same question with the change actually made rather than subtracted: one program solved REPS
    // times through a fresh CachedCeresSolvers per solve, then through one shared across all of them.
    // Nothing but the caching differs, and the Kotlin-side work is out of the denominator, so this is
    // the upper bound on what hoisting the cache is worth.
    println("\n$REPS solves of one bit-identical program, fresh cache per solve vs one shared:")
    println("%-5s %5s   %11s %11s %9s".format("solid", "V", "fresh", "shared", "shared is"))
    for (case in CACHE_CASES) {
        val seed = conway(case.expression).polyhedron
        val problem = RealizationProblem.of(seed)
        val cloud = PointCloud(problem.initialCoordinates)
        val program = CeresRealization.program(
            plan,
            problem,
            options.planes,
            options.gaugeRotationWeight,
            options.minEdgeLengthFraction,
        )
        val request = SolveRequest(cloud, program, options = solveOptions)

        fun freshSolves() = repeat(REPS) {
            CachedCeresSolvers(FfmCeresBackend()).use { it.solve(request) }
        }

        fun sharedSolves() = CachedCeresSolvers(FfmCeresBackend()).use { solvers ->
            repeat(REPS) { solvers.solve(request) }
        }

        // The big two take tens of seconds for one pass of REPS solves, and the ratio is already
        // decided by then, so they get a single unwarmed pass rather than a best-of.
        val heavy = problem.vertexCount > 100
        val warmup = if (heavy) 0 else 1
        val reps = if (heavy) 1 else 3
        val (_, freshNanos) = best(warmup, reps) { freshSolves() }
        val (_, sharedNanos) = best(warmup, reps) { sharedSolves() }
        println(
            "%-5s %5d   %9.1fms %9.1fms %8.2fx".format(
                case.expression,
                problem.vertexCount,
                freshNanos / 1e6,
                sharedNanos / 1e6,
                freshNanos.toDouble() / sharedNanos,
            ),
        )
    }

    // And the reason even that upper bound overstates the animated case, which is the one a cache is
    // usually wanted for. CachedCeresSolvers keys on point count plus program hash, and
    // CeresRealization.program bakes the defaults it resolves from the *initial embedding* — mean edge
    // length, centroid, tangency radius, the minimum-edge-length floor — into the program. So the same
    // topology with moved vertices is a different program and a guaranteed miss: a cache across the
    // frames of a morphing solid never hits at all, whatever it costs to keep.
    println("\nDistinct programs across $REPS frames of one topology with the geometry moved:")
    println("%-5s %5s   %s".format("solid", "V", "distinct programs (misses)"))
    for (case in CACHE_CASES) {
        val seed = conway(case.expression).polyhedron
        val seedPoints = seed.realization!!
        val vertexCount = seedPoints.size

        // A deterministic wobble, the scale a keyframed animation would move a vertex by.
        fun moved(step: Int): PolyhedralObject = seed.copy(
            realization = Realization3(
                (0 until vertexCount).map { i ->
                    val v = seedPoints[i]
                    val s = 1.0f + 0.001f * sin(0.7f * step + 0.3f * i)
                    Vec3(v.x * s, v.y * s, v.z * s)
                },
            ),
        )

        val distinct = (0 until REPS).map { step ->
            CeresRealization.program(
                plan,
                RealizationProblem.of(moved(step)),
                options.planes,
                options.gaugeRotationWeight,
                options.minEdgeLengthFraction,
            ).legacyHash()
        }.toSet().size
        println("%-5s %5d   %d of %d".format(case.expression, vertexCount, distinct, REPS))
    }
}
