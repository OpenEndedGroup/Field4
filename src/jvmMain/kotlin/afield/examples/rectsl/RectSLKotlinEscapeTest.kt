package afield.examples.rectsl

import afield.KotlinInterface2
import afield.rectsl.compileRectSL

/*
FIELD_TEST
id: rectsl.kotlin-escape
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLKotlinEscapeTest
tags: [rectsl, kotlin, k2, functions, experimental]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl kotlin escape passed"
*/
/**
 * Experimental: `{{ … }}` escapes let a RectSL program drop into a live
 * `with(Functions){ … }` Kotlin scope anywhere a value is expected — the escape
 * evaluates once (K2), is cached by source, and pushes a Number, an `Fn`, or a
 * `Track<Double>` onto the stack. This exercises the whole path with a real engine.
 */
object RectSLKotlinEscapeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val ki = KotlinInterface2(filePrefix = "RECTSL_ESCAPE_")

        // 1) An escape that returns a Number, used directly as a coordinate.
        val numeric = compileRectSL(
            """
            wall 10 10 meters
            duration 1
            fps 1
            "a" rect  {{ 1.0 + 2.0 }} 0 xy  1 square ;
            """.trimIndent(),
            engine = ki
        ).programOrNull ?: error("numeric escape failed to compile")
        val ax = numeric.sample(0.0).rects.first().center.x
        require(kotlin.math.abs(ax - 3.0) < 1e-9) { "numeric escape expected x=3.0, got $ax" }

        // 2) An escape that returns an Fn, sampled per copy across an array. The escape
        //    sits inside the loop quote — proving the per-source cache (compiled once).
        val bell = compileRectSL(
            """
            wall 20 8 meters
            duration 1
            fps 1
            "c" rect 0 0 xy 1 square
              5 '( ic 2 * 0 offset  {{ gaussian(0.5, 0.16) }} f sample alpha ) array
            ;
            """.trimIndent(),
            engine = ki
        ).programOrNull ?: error("Fn escape failed to compile")
        val alphas = bell.sample(0.0).rects.sortedBy { it.center.x }.map { it.alpha }
        require(alphas.size == 5) { "expected 5 rects, got ${alphas.size}" }
        require(kotlin.math.abs(alphas[2] - 1.0) < 1e-3) { "bell center should be ~1, got ${alphas[2]}" }
        require(alphas[0] < 0.05 && alphas[4] < 0.05) { "bell edges should be dark: $alphas" }

        // 2b) A per-copy escape: compiled once, then invoked per copy with a different
        //     i, so the escaped Kotlin varies across the array at ~free marginal cost.
        val perCopy = compileRectSL(
            """
            wall 20 8 meters
            duration 1
            fps 1
            "c" rect 0 0 xy 1 square
              5 '( {{ i * 2.0 }} 0 offset ) array
            ;
            """.trimIndent(),
            engine = ki
        ).programOrNull ?: error("per-copy escape failed to compile")
        val pxs = perCopy.sample(0.0).rects.map { it.center.x }.sorted()
        listOf(0.0, 2.0, 4.0, 6.0, 8.0).forEachIndexed { idx, e ->
            require(kotlin.math.abs(pxs[idx] - e) < 1e-9) { "per-copy escape expected $e at $idx, got ${pxs[idx]}" }
        }

        // 3) An escape that returns a value from the Functions transform pipeline.
        val pipe = compileRectSL(
            """
            wall 6 6 meters
            duration 1
            fps 1
            "p" rect 0 0 xy 1 square  {{ (sin(2) through gain(0.5)).eval(0.25) }} alpha ;
            """.trimIndent(),
            engine = ki
        ).programOrNull
        require(pipe != null) { "transform-pipeline escape failed to compile" }
        val pa = pipe.sample(0.0).rects.first().alpha
        require(pa in 0.0..1.0) { "pipeline alpha out of range: $pa" }

        // 4) A missing engine is an honest, specific error — never a silent zero.
        val noEngine = compileRectSL(
            """
            wall 4 4 meters
            "z" rect {{ 1.0 }} 0 xy 1 square ;
            """.trimIndent()
        )
        require(noEngine.programOrNull == null && noEngine.diagnostics.any { it.contains("Kotlin escape needs") }) {
            "escape without an engine should fail with a clear message, got ${noEngine.diagnostics}"
        }

        println("rectsl kotlin escape passed")
    }
}
