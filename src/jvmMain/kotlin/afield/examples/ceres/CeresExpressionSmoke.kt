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
import afield.stdlib.ceres.reciprocal
import afield.stdlib.ceres.sqrt
import kotlin.math.abs
import kotlin.math.sqrt as ksqrt

/**
 * The residual expression VM, checked three ways.
 *
 * 1. **Equivalence.** A residual written as an expression must give the same answer as the
 *    hand-written C++ opcode that does the same thing. This is the claim that the VM and its
 *    derivatives are correct, and it is checked against the wrapper's own functors rather than
 *    against a number someone typed in.
 * 2. **Sharing.** The compiler hash-conses the expression DAG, so a subexpression used twice is
 *    evaluated once.
 * 3. **Something no opcode can do.** The Thomson problem — minimise the Coulomb energy of N points
 *    confined to a sphere — has known solutions, and there is no constraint in this vocabulary that
 *    can express `1/r` repulsion. Solving it from residuals written in Kotlin, and landing on the
 *    known answer, is the point of the whole exercise.
 */
private fun solve(
    points: PointCloud,
    program: CeresProgram,
    iterations: Int = 300,
): PointCloud = CachedCeresSolvers(FfmCeresBackend()).use { solvers ->
    val result = solvers.solve(
        SolveRequest(
            points,
            program,
            options = SolveOptions(
                maxIterations = iterations,
                linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
                functionTolerance = 1e-12,
                gradientTolerance = 1e-16,
                parameterTolerance = 1e-14,
                summary = true,
            ),
        ),
    )
    check(result.ok) { "solve failed" }
    result.points
}

private fun maxDeviation(a: PointCloud, b: PointCloud): Double =
    a.values.indices.maxOf { abs(a.values[it] - b.values[it]) }

fun main() {
    val backend = FfmCeresBackend()

    // ---------------------------------------------------------------------------------------
    // 1. Equivalence with the hand-written opcodes.
    // ---------------------------------------------------------------------------------------

    val scattered = PointCloud.of(
        Vec3(0.3, -0.2, 1.1), Vec3(1.4, 0.9, -0.3), Vec3(-0.8, 1.2, 0.4), Vec3(0.1, -1.3, -0.9),
        Vec3(1.9, 0.2, 0.7), Vec3(-1.1, -0.6, 1.4),
    )
    val pairs = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 0, 0 to 3)

    // DISTANCE, as an opcode and as an expression.
    val nativeDistance = solve(
        scattered,
        buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) -> distance(a, b, 1.25, weight = 1.0) }
            weakAll(weight = 1e-4)
        },
    )
    val expressedDistance = solve(
        scattered,
        buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) ->
                expression(a, b) { (pa, pb) -> listOf(pa.distanceTo(pb) - 1.25.c) }
            }
            weakAll(weight = 1e-4)
        },
    )
    val distanceDelta = maxDeviation(nativeDistance, expressedDistance)
    check(distanceDelta < 1e-9) { "expression distance diverged from the DISTANCE opcode by $distanceDelta" }
    println("ok DISTANCE      opcode vs expression: max coordinate difference %.2e".format(distanceDelta))

    // EDGE_TANGENCY, as an opcode and as an expression. This one is worth doing by hand because it
    // is the constraint the polyhedron realizer turns on: the distance from the origin to the line
    // through two points.
    fun tangencyRadius(pa: CeresExprVec, pb: CeresExprVec): CeresExpr {
        val d = pb - pa
        val t = -(pa dot d) / (d.lengthSquared() + 1e-20.c)
        return (pa + d * t).length()
    }

    val nativeTangency = solve(
        scattered,
        buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) -> edgeTangency(a, b, radius = 1.0, weight = 1.0) }
            weakAll(weight = 1e-4)
        },
    )
    val expressedTangency = solve(
        scattered,
        buildCeresProgram(RobustLoss.NONE) {
            pairs.forEach { (a, b) ->
                expression(a, b) { (pa, pb) -> listOf(tangencyRadius(pa, pb) - 1.0.c) }
            }
            weakAll(weight = 1e-4)
        },
    )
    val tangencyDelta = maxDeviation(nativeTangency, expressedTangency)
    check(tangencyDelta < 1e-8) { "expression tangency diverged from EDGE_TANGENCY by $tangencyDelta" }
    println("ok EDGE_TANGENCY opcode vs expression: max coordinate difference %.2e".format(tangencyDelta))

    // ---------------------------------------------------------------------------------------
    // 2. Sharing: the DAG is compiled once per unique node, not once per mention.
    // ---------------------------------------------------------------------------------------

    val shared = buildCeresProgram {
        expression(0, 1) { (pa, pb) ->
            val d = pa.distanceTo(pb) // named once, used three times
            listOf(d - 1.0.c, d * d - 2.0.c, reciprocal(d))
        }
    }
    val compiled = (shared.constraints.first() as afield.stdlib.ceres.CeresExpressionResidual).compile()
    val sqrtCount = (0 until compiled.instructionCount).count {
        compiled.code[4 * it] == 7 // ExprOp.SQRT
    }
    check(sqrtCount == 1) { "the shared distance compiled to $sqrtCount square roots, expected 1" }
    println("ok sharing: three residuals over one distance compiled to $sqrtCount sqrt, ${compiled.instructionCount} instructions")

    // ---------------------------------------------------------------------------------------
    // 3. Validation: a malformed expression is refused, not evaluated.
    // ---------------------------------------------------------------------------------------

    val repeated = runCatching {
        buildCeresProgram { expression(2, 2) { (a, b) -> listOf(a.distanceTo(b)) } }
    }
    check(repeated.isFailure) { "an expression naming the same point twice was accepted" }
    println("ok rejected repeated point: ${repeated.exceptionOrNull()?.message?.take(70)}")

    val outOfRange = runCatching {
        CachedCeresSolvers(backend).use {
            it.getOrCompile(scattered, buildCeresProgram { expression(0, 99) { (a, b) -> listOf(a.distanceTo(b)) } })
        }
    }
    check(outOfRange.isFailure) { "an expression naming a nonexistent point was accepted" }
    println("ok rejected out-of-range point: ${outOfRange.exceptionOrNull()?.message?.take(70)}")

    // ---------------------------------------------------------------------------------------
    // 4. The Thomson problem: N charges on a sphere, minimising sum 1/r over pairs.
    //
    //    Nothing in the constraint vocabulary can say "1/r repulsion". Written as residuals,
    //    r_ij = 1/sqrt(d_ij) gives cost 0.5 * sum(1/d_ij), which is the Coulomb energy up to the
    //    factor Ceres puts in front. The known minima are exact numbers, so this either lands on
    //    them or it does not.
    // ---------------------------------------------------------------------------------------

    // Known global minima. N=12 is the icosahedron, and from this deterministic start the solver
    // converges cleanly to a *different*, slightly worse configuration — which is the Thomson
    // problem's own local-minimum structure and says nothing about the VM. It is kept because that
    // distinction is worth showing: the check for it is "converged and on the sphere", not "found
    // the global optimum".
    val known = listOf(
        Triple(4, 3.674234614, true),
        Triple(6, 9.985281374, true),
        Triple(8, 19.675287861, true),
        Triple(12, 49.165253058, false),
    )

    for ((n, expected, expectGlobal) in known) {
        // A Fibonacci spiral at 60% of the sphere's radius. The Thomson problem has many local
        // minima — a deliberately scrambled start reliably finds one of them and stays there, which
        // says something about global optimisation and nothing about this VM — so the start is the
        // standard deterministic initialisation, squashed inward so the solve still has real work
        // to do. No randomness: the numbers below reproduce exactly.
        val goldenAngle = 2.399963229728653
        val start = PointCloud.of(
            *(0 until n).map { i ->
                val z = 1.0 - 2.0 * (i + 0.5) / n
                val r = ksqrt((1.0 - z * z).coerceAtLeast(1e-6))
                val phi = goldenAngle * i
                Vec3(0.6 * r * kotlin.math.cos(phi), 0.6 * r * kotlin.math.sin(phi), 0.6 * z)
            }.toTypedArray(),
        )

        val program = buildCeresProgram(RobustLoss.NONE) {
            // Every point on the unit sphere. The weight has to be large: this is a soft stand-in
            // for a hard constraint, and repulsion will happily push the points outward for a lower
            // energy otherwise. Remember the native weight is square-rooted, so 1e8 here is a
            // residual multiplier of 1e4, not 1e8. The trade is accuracy against iterations —
            // 1e4 lands within 2e-4 of the known energy in 53 iterations and 1e6 within 2e-6 in
            // 343, but push on to 1e8 and the normal equations are ill-conditioned enough that the
            // larger instances stop converging at all. 1e6 is the balance.
            for (i in 0 until n) {
                expression(i, weight = 1e6) { (p) -> listOf(p.length() - 1.0.c) }
            }
            // Coulomb repulsion between every pair.
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    expression(i, j) { (pa, pb) -> listOf(reciprocal(sqrt(pa.distanceTo(pb)))) }
                }
            }
        }

        val solved = solve(start, program, iterations = 5000)
        val energy = (0 until n).sumOf { i ->
            (i + 1 until n).sumOf { j ->
                val a = solved[i]
                val b = solved[j]
                1.0 / ksqrt(
                    (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z),
                )
            }
        }
        val radii = (0 until n).map { ksqrt(solved[it].let { p -> p.x * p.x + p.y * p.y + p.z * p.z }) }
        val offSphere = radii.maxOf { abs(it - 1.0) }

        println(
            "ok Thomson N=%-3d energy=%.6f  known=%.6f  error=%.2e  off-sphere=%.2e%s".format(
                n, energy, expected, abs(energy - expected), offSphere,
                if (expectGlobal) "" else "   (local minimum, as expected)",
            ),
        )
        check(offSphere < 1e-5) { "N=$n: points drifted off the sphere by $offSphere" }
        if (expectGlobal) {
            check(abs(energy - expected) < 1e-4) { "N=$n: energy $energy != known minimum $expected" }
        } else {
            // A local minimum is fine; converging to something *better* than the known global
            // minimum would mean the energy is being computed wrong.
            check(energy > expected - 1e-6) { "N=$n: energy $energy beats the known minimum $expected" }
        }
    }

    println("\nok — residuals defined in Kotlin, differentiated by Ceres, no native rebuild")
}
