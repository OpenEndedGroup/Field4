package afield.examples.ceres

import afield.math.optimization.ceres.CachedCeresSolvers
import afield.math.optimization.ceres.CeresExpr
import afield.math.optimization.ceres.CeresExprVec
import afield.math.optimization.ceres.CeresLinearSolver
import afield.math.optimization.ceres.CeresProgram
import afield.math.optimization.ceres.FfmCeresBackend
import afield.math.optimization.ceres.PointCloud
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.SolveOptions
import afield.math.optimization.ceres.SolveRequest
import afield.math.optimization.ceres.Vec3
import afield.math.optimization.ceres.buildCeresProgram
import afield.math.optimization.ceres.c
import afield.geometry.polytope.CeresRealization
import afield.geometry.polytope.PlaneFormulation
import afield.geometry.polytope.RealizationProblem
import afield.geometry.polytope.RelaxationPlan
import afield.geometry.polytope.conway

/**
 * What the expression VM costs against the equivalent hand-written functor.
 *
 * Same problem, same answer, same iteration count — the only difference is whether the residual is
 * a compiled C++ template or an interpreted register program. This is the number to look at when
 * deciding whether a residual has earned its own opcode.
 */
private fun timeSolve(points: PointCloud, program: CeresProgram, reps: Int = 20): Pair<Long, Int> {
    var best = Long.MAX_VALUE
    var iterations = 0
    CachedCeresSolvers(FfmCeresBackend()).use { solvers ->
        val options = SolveOptions(
            maxIterations = 100,
            linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
            summary = true,
        )
        repeat(3) { solvers.solve(SolveRequest(points, program, options = options)) }
        repeat(reps) {
            val t = System.nanoTime()
            val r = solvers.solve(SolveRequest(points, program, options = options))
            best = minOf(best, System.nanoTime() - t)
            iterations = r.summary!!.iterations
        }
    }
    return best to iterations
}

fun main() {
    for (n in listOf(50, 200, 800)) {
        // A ring of points, each pulled toward a fixed spacing from the next.
        val cloud = PointCloud.of(
            *(0 until n).map { i ->
                val a = 2.0 * Math.PI * i / n
                Vec3(Math.cos(a) * (1.0 + 0.13 * Math.sin(7.0 * a)), Math.sin(a) * 1.07, 0.21 * Math.cos(3.0 * a))
            }.toTypedArray(),
        )
        val pairs = (0 until n).map { it to (it + 1) % n }

        val native = buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) -> distance(a, b, 0.1, weight = 1.0) }
            weakAll(weight = 1e-3)
        }
        val expressed = buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) ->
                expression(a, b) { (pa, pb): List<CeresExprVec> ->
                    listOf<CeresExpr>(pa.distanceTo(pb) - 0.1.c)
                }
            }
            weakAll(weight = 1e-3)
        }

        val (nativeNanos, nativeIters) = timeSolve(cloud, native)
        val (exprNanos, exprIters) = timeSolve(cloud, expressed)
        println(
            "n=%-4d  DISTANCE opcode %7.2f ms (%d iter)   expression %7.2f ms (%d iter)   x%.1f".format(
                n, nativeNanos / 1e6, nativeIters, exprNanos / 1e6, exprIters,
                exprNanos.toDouble() / nativeNanos,
            ),
        )
    }

    // The same measurement for a residual whose unknowns are not all points: PLANAR_HESSE, which is a
    // plane per face as a free unit normal on a sphere manifold plus a scalar offset, against
    // planarHesseExpression() saying the same thing in Kotlin. Same objective, same seed, same answer
    // to 1e-15 (see smokeCeresExpressionVariables), so the only difference is template against VM —
    // now with parameter blocks of two different sizes and a manifold in the mix.
    println()
    for (name in listOf("kC", "tI", "kkC", "kkD")) {
        val seed = conway(name).polyhedron
        val problem = RealizationProblem.of(seed)
        val plan = RelaxationPlan.canonicalConditions()
        val cloud = PointCloud(problem.initialCoordinates)

        fun program(planes: PlaneFormulation) = CeresRealization.program(
            plan,
            problem,
            planes,
            gaugeRotationWeight = 1.0,
        )

        val (opcodeNanos, opcodeIters) = timeSolve(cloud, program(PlaneFormulation.HESSE), reps = 10)
        val (exprNanos, exprIters) =
            timeSolve(cloud, program(PlaneFormulation.HESSE_EXPRESSION), reps = 10)
        println(
            ("%-4s F=%-4d PLANAR_HESSE opcode %7.2f ms (%d iter)   expression %7.2f ms (%d iter)   " +
                "x%.1f").format(
                name, seed.topology.elements(2).size,
                opcodeNanos / 1e6, opcodeIters, exprNanos / 1e6, exprIters,
                exprNanos.toDouble() / opcodeNanos,
            ),
        )
    }
}
