package afield.examples.ceres

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.polytope.CeresRealization
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.ConstraintResidualReport
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.RealizationSolveResult
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.ceresRealizationAvailable
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relax
import afield.stdlib.polytope.relaxWithCeres

/**
 * Runs the same [RelaxationPlan] through the Kotlin least-squares realizer and the Ceres one, on a
 * ladder of Conway solids, and prints what each achieved and what it cost.
 *
 * The point is not to declare a winner. It is to answer three questions with numbers: do the two
 * agree on the geometry, where does the crossover in cost sit, and does Ceres reach an answer on the
 * inputs where the existing iteration is documented not to.
 */
private data class Case(val expression: String, val label: String)

private val CASES = listOf(
    Case("C", "cube"),
    Case("tC", "truncated cube"),
    Case("kC", "kis cube"),
    Case("aC", "cuboctahedron"),
    Case("tI", "truncated icosahedron"),
    Case("kkC", "kis kis cube (documented hard case)"),
)

private fun report(reports: List<ConstraintResidualReport>): String =
    reports.filter { it.weight > 0.0 }.joinToString("  ") { "${it.name}=${"%.3e".format(it.rms)}" }

/**
 * Best-of-[reps] after [warmup] discarded runs.
 *
 * A single timing taken in a cold JVM measures the JIT, not the solver: the first case below was
 * reporting 17 ms for a solve that settles at well under one. Both solvers are warmed the same way
 * and the minimum is reported, which is the stable statistic for a deterministic computation.
 */
private fun <T> timed(warmup: Int = 3, reps: Int = 7, body: () -> T): Pair<T, Long> {
    repeat(warmup) { body() }
    var best = Long.MAX_VALUE
    var last: T? = null
    repeat(reps) {
        val start = System.nanoTime()
        last = body()
        best = minOf(best, System.nanoTime() - start)
    }
    @Suppress("UNCHECKED_CAST")
    return (last as T) to best
}

private fun summarise(label: String, result: RealizationSolveResult, nanos: Long, extra: String = "") {
    println(
        "    %-8s %8.2f ms  iters=%-4d %-14s rms=%.3e  %s%s".format(
            label,
            nanos / 1e6,
            result.iterations,
            if (result.converged) "converged" else result.reason,
            result.rmsResidual,
            report(result.finalConstraints),
            extra,
        ),
    )
}

fun main() {
    check(ceresRealizationAvailable()) {
        "no Ceres wrapper: build one with ./gradlew buildCeresRedistributable"
    }

    for (case in CASES) {
        val seed: PolyhedralObject = conway(case.expression).polyhedron
        val vertices = seed.topology.elements(0).size
        val edges = seed.topology.elements(1).size
        val faces = seed.topology.elements(2).size
        println("\n${case.expression}  (${case.label}) — V=$vertices E=$edges F=$faces")

        for ((planName, plan) in listOf(
            "regularize" to RelaxationPlan.regularize(
                leastSquares = LeastSquaresOptions(maxIterations = 200),
            ),
            "canonical" to RelaxationPlan.canonicalConditions(
                leastSquares = LeastSquaresOptions(maxIterations = 200),
            ),
        )) {
            val missing = CeresRealization.unsupported(plan)
            if (missing.isNotEmpty()) {
                println("  $planName: skipped, no Ceres equivalent for $missing")
                continue
            }
            println("  $planName")

            val (kotlinResult, kotlinNanos) = timed { seed.relax(plan) }
            summarise("kotlin", kotlinResult, kotlinNanos)

            val (ceres, ceresNanos) = timed {
                seed.relaxWithCeres(
                    plan,
                    CeresRelaxationOptions(
                        maxIterations = 200,
                        linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
                    ),
                )
            }
            summarise("ceres", ceres.realization, ceresNanos, "  [${ceres.summary.linearSolverUsed}]")
            println("             speedup x%.1f".format(kotlinNanos.toDouble() / ceresNanos))
        }
    }
}
