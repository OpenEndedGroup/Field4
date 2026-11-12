package afield.examples.stdlib

import afield.graphics.vulkan.util.FMChirpCurves
import afield.math.optimization.LeastSquaresOptions
import afield.math.optimization.leastSquares
import afield.tools.testing.Checks
import kotlin.math.abs
import kotlin.math.sin

/*
FIELD_TEST
id: stdlib.least-squares-fmchirp.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.LeastSquaresAndFMChirpSmokeTest
tags: [smoke, stdlib, numerics, fmchirp]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "LeastSquares and FMChirp smoke tests passed"
*/
/**
 * The public example of structured checks (`afield.tools.testing.Checks`): every tolerance below is
 * one `FIELD_CHECK` record that `tools/run_field_tests.py` tabulates and writes into `results.json`.
 */
object LeastSquaresAndFMChirpSmokeTest {

    fun runTests(checks: Checks = Checks()): Boolean {
        return try {
            println("Running LeastSquares and FMChirp smoke tests...")
            testLeastSquaresFindsSmallNonlinearSolution(checks)
            testFMChirpPointModifier(checks)
            testFMChirpOrderedAreaModifier(checks)
            checks.requireAll()
            println("LeastSquares and FMChirp smoke tests passed")
            true
        } catch (t: Throwable) {
            println("LeastSquares and FMChirp smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "LeastSquares and FMChirp smoke tests failed" }
    }

    private fun testLeastSquaresFindsSmallNonlinearSolution(checks: Checks) {
        val result = leastSquares(
            initial = doubleArrayOf(0.2, -0.1),
            options = LeastSquaresOptions(maxIterations = 80)
        ) { x ->
            doubleArrayOf(
                x[0] + 2.0 * x[1] - 1.0,
                sin(x[0]) - 0.25
            )
        }

        val solver = "least-squares convergence target for an exactly solvable 2x2 system"
        checks.that("least-squares cost", result.cost, "< 1e-10", provenance = solver) { it < 1e-10 }
        checks.near("linear residual x0 + 2 x1 - 1", result.x[0] + 2.0 * result.x[1] - 1.0, 0.0, tolerance = 1e-5, provenance = solver)
        checks.near("nonlinear residual sin(x0) - 0.25", sin(result.x[0]) - 0.25, 0.0, tolerance = 1e-5, provenance = solver)
    }

    private fun testFMChirpPointModifier(checks: Checks) {
        val base = FMChirpCurves.Presets.neutral()
        val targetX = 0.5f
        val targetY = 0.68f
        val shaped = base.shape(
            FMChirpCurves.Modifiers.hit(targetX, targetY, weight = 25f),
            options = FMChirpCurves.FitOptions(
                leastSquares = LeastSquaresOptions(maxIterations = 60),
                regularizationWeight = 0.01f
            )
        )

        val beforeError = abs(base.eval(targetX) - targetY)
        val afterError = abs(shaped.eval(targetX) - targetY)
        checks.that(
            "point modifier hit error", afterError.toDouble(), "< 0.35 x unshaped error ($beforeError)",
            note = "shaped=$shaped", provenance = "FMChirp hit modifier contract: pulls the curve toward the target",
        ) { it < beforeError * 0.35f }
    }

    private fun testFMChirpOrderedAreaModifier(checks: Checks) {
        val base = FMChirpCurves.Presets.neutral()
        val targetX = 0.5f
        val targetY = 0.62f
        val shaped = base.shape(
            FMChirpCurves.Modifiers.hit(targetX, targetY, weight = 18f),
            FMChirpCurves.Modifiers.areaDelta(0.08f, weight = 8f),
            options = FMChirpCurves.FitOptions(
                leastSquares = LeastSquaresOptions(maxIterations = 60),
                regularizationWeight = 0.02f
            )
        )

        val baseArea = base.area()
        val shapedArea = shaped.area()
        val ordered = "FMChirp ordered modifiers: areaDelta raises area without abandoning the hit"
        checks.that("area after areaDelta(0.08)", shapedArea.toDouble(), "> base area ($baseArea) + 0.025", provenance = ordered) {
            it > baseArea + 0.025f
        }
        checks.near("ordered hit y at x=0.5", shaped.eval(targetX).toDouble(), targetY.toDouble(), tolerance = 0.18, provenance = ordered)
    }
}
