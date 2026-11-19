package afield.lang.stacksl.cpu

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Interpreted vs specialized: wall-clock for the same StackSL programs, single core, with the
 * output checked bit-equal on every run timed.
 *
 * Same method as [StackSLCpuThroughputMeasurements]: [WARMUP] untimed renders so the JIT has
 * compiled both paths, then the median of [REPEATS]. Three columns: the interpreter on the
 * original shader, the interpreter on the *printed residual GLSL* (what specialization buys an
 * interpreter on its own), and the residual's register program. The specialization time itself is
 * reported separately, because it is paid once per edit of the program, not per pixel.
 *
 * In `jvmMeasurements`, not `check`: wall-clock under load is not a regression signal. The only
 * assertion is that the residual is not slower than the interpreter.
 */
class GlslPartialEvaluatorMeasurements {

    private val programs = listOf(
        "uv saturate",
        "uv 4.0 * fbm palette saturate",
        "p 6 kaleido fbm palette saturate",
        "uv 0.5 0.5 vec2 - length 20 * time - sin 0.5 * 0.5 +",
    )

    @Test
    fun `interpreted vs residual, one core`() {
        val template = StackSLCpu.stillTemplate()
        println("--- StackSL: interpreter vs partial-evaluated residual, ${DIM}x$DIM, one core, median of $REPEATS ---")
        println("%-44s %8s %8s %9s %9s %8s %8s %7s".format("program", "glslLn", "resOps", "spec ms", "interp s", "resGlsl s", "resJvm s", "speedup"))
        var worst = Double.MAX_VALUE
        for (program in programs) {
            val source = StackSLCpu.expandStackAuto(template, program)
            val t0 = System.nanoTime()
            val residual = GlslPartialEvaluator.specialize(source)
            val specMs = (System.nanoTime() - t0) / 1e6
            val residualGlsl = residual.toGlsl()

            val reference = StackSLCpu.renderFragment(source, DIM, DIM, parallel = false)
            fun time(render: () -> CpuRaster): Double {
                repeat(WARMUP) { render() }
                return (0 until REPEATS).map {
                    val started = System.nanoTime()
                    val r = render()
                    val s = (System.nanoTime() - started) / 1e9
                    assertTrue(r.rgba.indices.all { i -> r.rgba[i].toRawBits() == reference.rgba[i].toRawBits() }, "$program: output differs")
                    s
                }.sorted()[REPEATS / 2]
            }
            val interp = time { StackSLCpu.renderFragment(source, DIM, DIM, parallel = false) }
            val resGlsl = time { StackSLCpu.renderFragment(residualGlsl, DIM, DIM, parallel = false) }
            val resJvm = time { residual.render(DIM, DIM, parallel = false) }
            val speedup = interp / resJvm
            worst = minOf(worst, speedup)
            println(
                "%-44s %8d %8d %9.1f %9.3f %9.3f %8.3f %6.1fx".format(
                    program, source.lines().size, residual.operationCount, specMs, interp, resGlsl, resJvm, speedup
                )
            )
        }
        assertTrue(worst > 1.0, "the residual was slower than the interpreter (worst speedup ${"%.2f".format(worst)}x)")
    }

    private companion object {
        const val DIM = 96
        const val WARMUP = 3
        const val REPEATS = 5
    }
}
