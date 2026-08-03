package afield.examples.ceres

import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresExpr
import afield.stdlib.ceres.CeresExprVec
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.CeresProgram
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.buildCeresProgram
import afield.stdlib.ceres.c

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
}
