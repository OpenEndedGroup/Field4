package afield.examples.stdlib

import afield.four.util.FMChirpCurves
import afield.stdlib.LeastSquaresOptions
import afield.stdlib.leastSquares
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
object LeastSquaresAndFMChirpSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running LeastSquares and FMChirp smoke tests...")
            testLeastSquaresFindsSmallNonlinearSolution()
            testFMChirpPointModifier()
            testFMChirpOrderedAreaModifier()
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

    private fun testLeastSquaresFindsSmallNonlinearSolution() {
        val result = leastSquares(
            initial = doubleArrayOf(0.2, -0.1),
            options = LeastSquaresOptions(maxIterations = 80)
        ) { x ->
            doubleArrayOf(
                x[0] + 2.0 * x[1] - 1.0,
                sin(x[0]) - 0.25
            )
        }

        require(result.cost < 1e-10) { "Expected tiny least-squares cost, got ${result.cost}" }
        require(abs(result.x[0] + 2.0 * result.x[1] - 1.0) < 1e-5) { "Linear residual remained high" }
        require(abs(sin(result.x[0]) - 0.25) < 1e-5) { "Nonlinear residual remained high" }
    }

    private fun testFMChirpPointModifier() {
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
        require(afterError < beforeError * 0.35f) {
            "Point modifier did not improve enough: before=$beforeError after=$afterError shaped=$shaped"
        }
    }

    private fun testFMChirpOrderedAreaModifier() {
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
        require(shapedArea > baseArea + 0.025f) {
            "Area modifier did not increase area enough: base=$baseArea shaped=$shapedArea"
        }
        require(abs(shaped.eval(targetX) - targetY) < 0.18f) {
            "Ordered modifiers wandered too far from hit target: y=${shaped.eval(targetX)} target=$targetY"
        }
    }
}
