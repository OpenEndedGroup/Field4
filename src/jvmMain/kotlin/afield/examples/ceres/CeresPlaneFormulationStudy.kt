package afield.examples.ceres

import afield.stdlib.LeastSquaresOptions
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.PlaneFormulation
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relax
import afield.stdlib.polytope.relaxWithCeres

/**
 * How the plane parameterisation and the rotation gauge affect the canonical realization.
 *
 * `PLANAR`'s residual `(P - O) . n` sees the plane origin only through `O . n`, so two of `O`'s
 * three components are an exact null direction — two per face. The question this answers is how
 * much of the Ceres realizer's trouble on the kis solids was that, and how much is the problem.
 */
private val CASES = listOf("C", "aC", "tC", "kC", "tI", "kkC")

private fun <T> best(warmup: Int = 2, reps: Int = 5, body: () -> T): Pair<T, Long> {
    repeat(warmup) { body() }
    var b = Long.MAX_VALUE
    var last: T? = null
    repeat(reps) {
        val t = System.nanoTime()
        last = body()
        b = minOf(b, System.nanoTime() - t)
    }
    @Suppress("UNCHECKED_CAST")
    return (last as T) to b
}

fun main() {
    println(
        "%-5s %-22s %6s %6s %5s %-14s %11s %11s".format(
            "solid", "planes", "params", "resid", "iter", "termination", "tangency", "planarity",
        ),
    )
    for (expr in CASES) {
        val seed = conway(expr).polyhedron
        val plan = RelaxationPlan.canonicalConditions(
            leastSquares = LeastSquaresOptions(maxIterations = 200),
        )

        val (k, kn) = best { seed.relax(plan) }
        println(
            "%-5s %-22s %6s %6s %5d %-14s %11.3e %11.3e   %7.2f ms".format(
                expr, "kotlin (newell fit)", "-", "-", k.iterations,
                if (k.converged) "converged" else k.reason,
                k.finalConstraints.first { it.name == "edge-tangency" }.rms,
                k.finalConstraints.first { it.name == "face-planarity" }.rms,
                kn / 1e6,
            ),
        )

        for (planes in PlaneFormulation.entries) {
            for (gauge in listOf(0.0, 1.0)) {
                val label = planes.name.lowercase() + if (gauge > 0) " + gauge" else ""
                val (c, cn) = best {
                    seed.relaxWithCeres(
                        plan,
                        CeresRelaxationOptions(
                            maxIterations = 200,
                            planes = planes,
                            gaugeRotationWeight = gauge,
                        ),
                    )
                }
                println(
                    "%-5s %-22s %6d %6d %5d %-14s %11.3e %11.3e   %7.2f ms".format(
                        expr, label,
                        c.summary.effectiveParameterCount,
                        c.summary.residualCount,
                        c.summary.iterations,
                        c.summary.termination.name.lowercase(),
                        c.realization.finalConstraints.first { it.name == "edge-tangency" }.rms,
                        c.realization.finalConstraints.first { it.name == "face-planarity" }.rms,
                        cn / 1e6,
                    ),
                )
            }
        }
        println()
    }
}
