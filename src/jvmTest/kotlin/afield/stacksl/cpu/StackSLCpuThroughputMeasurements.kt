package afield.stacksl.cpu

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How fast is the StackSL CPU backend, actually?
 *
 * `dev_docs/STACKSL_CPU_BACKEND.md` and `StackSLCpu.renderStill`'s KDoc both claimed **10⁵**
 * pixel-shader invocations per second per core. This is where that number comes from now, and the
 * measured answer is **1.34e4 and 1.56e4** on two runs — about **7x** smaller than the claim. The
 * derived half of the old claim ("512x512 is single-digit seconds across ten cores") was CORRECT
 * and is unchanged: the two halves were never consistent with each other, and it was the
 * wall-clock half that was true.
 *
 * #### Method, stated so the number can be disputed
 *
 * One `GlslInvocation` per row, `parallel = false`, so the figure is per core and not per machine.
 * Each program is compiled and parsed once outside the timed region — parsing is a fixed cost and
 * the claim is about invocations. [WARMUP] untimed renders first, so the JIT has compiled the
 * interpreter's `eval`/`exec` before anything is recorded, then [REPEATS] timed renders and the
 * **median** is taken, because a mean is hostage to one GC pause. Invocations are `dim * dim` —
 * `main()` runs exactly once per pixel.
 *
 * The aggregate figure multiplies by nothing: it is a separate timed run with `parallel = true`,
 * so what it measures is what the machine does, including whatever else is on it.
 *
 * Measured on GraalVM CE 25.0.2, Apple aarch64, 10 cores — the numbers the docs now carry are the
 * ones this printed. This class is in `jvmMeasurements`, not `check`: a wall-clock figure under a
 * sibling nightly's Kotlin daemon is not a regression signal, and the recorded lesson on this
 * machine is that a 3.5x slowdown under load is load, not a defect. The bands below are therefore
 * wide enough to survive contention and still narrow enough to catch an order of magnitude.
 */
class StackSLCpuThroughputMeasurements {

    /** Programs spanning the cost range, with the five-octave `fbm` as the headline. */
    private val programs = listOf(
        "uv saturate" to "uv only: the cheapest program in the gallery",
        "uv 4.0 * fbm palette saturate" to "five-octave fbm + cosine palette: the headline figure",
        "p 6 kaleido fbm palette saturate" to "kaleido warp then fbm: the gallery's most expensive"
    )

    @Test
    fun `pixel-shader invocations per second per core`() {
        val template = StackSLCpu.stillTemplate()
        val results = programs.map { (program, label) ->
            val source = StackSLCpu.expandStackAuto(template, program)
            repeat(WARMUP) { StackSLCpu.renderFragment(source, DIM, DIM, parallel = false) }
            val timings = (0 until REPEATS).map {
                val started = System.nanoTime()
                StackSLCpu.renderFragment(source, DIM, DIM, parallel = false)
                (System.nanoTime() - started) / 1e9
            }.sorted()
            val median = timings[REPEATS / 2]
            val perCore = (DIM * DIM) / median
            Triple(label, perCore, median)
        }

        println("--- StackSL CPU backend throughput, ${DIM}x$DIM, parallel = false ---")
        println("warmup $WARMUP renders, $REPEATS timed renders, median reported")
        results.forEach { (label, perCore, median) ->
            println(
                "  %,10.0f invocations/s/core  (median %.3f s for ${DIM * DIM} pixels)  %s"
                    .format(perCore, median, label)
            )
        }

        val headline = results[1].second
        println("headline (five-octave fbm + palette): %,.0f invocations/s/core".format(headline))
        println(
            "derived: 512x512 = %,d invocations, so %.1f s on one core"
                .format(512 * 512, (512.0 * 512.0) / headline)
        )

        // Aggregate, measured rather than multiplied.
        val source = StackSLCpu.expandStackAuto(template, programs[1].first)
        repeat(2) { StackSLCpu.renderFragment(source, AGGREGATE_DIM, AGGREGATE_DIM, parallel = true) }
        val aggregate = (0 until REPEATS).map {
            val started = System.nanoTime()
            StackSLCpu.renderFragment(source, AGGREGATE_DIM, AGGREGATE_DIM, parallel = true)
            (AGGREGATE_DIM * AGGREGATE_DIM) / ((System.nanoTime() - started) / 1e9)
        }.sorted()[REPEATS / 2]
        println(
            ("aggregate (parallel = true, ${AGGREGATE_DIM}x$AGGREGATE_DIM): %,.0f invocations/s, " +
                "%.1fx the single-core figure on $CORES cores").format(aggregate, aggregate / headline)
        )
        println(
            "derived from the MEASURED aggregate: 512x512 takes %.1f s across $CORES cores"
                .format((512.0 * 512.0) / aggregate)
        )

        assertTrue(
            headline in HEADLINE_LOW..HEADLINE_HIGH,
            ("the headline figure is %,.0f invocations/s/core, outside the recorded band %,.0f..%,.0f. " +
                "If the machine is loaded this is load, not a regression — but if it is repeatable, " +
                "the two published figures need correcting again.")
                .format(headline, HEADLINE_LOW, HEADLINE_HIGH)
        )
        assertTrue(
            headline < 1e5,
            ("the headline figure reached %,.0f invocations/s/core, at or above the 10^5 this backend " +
                "used to claim — if that is repeatable, restore the old number in both places")
                .format(headline)
        )
        assertTrue(
            aggregate > headline * 2.0,
            ("parallel rendering achieved only %,.0f invocations/s against %,.0f on one core, which " +
                "is under 2x on a $CORES-core machine").format(aggregate, headline)
        )
    }

    private companion object {
        const val DIM = 96
        const val AGGREGATE_DIM = 192
        const val WARMUP = 3
        const val REPEATS = 5
        const val CORES = 10

        /** Measured 1.34e4 and 1.56e4 on two runs; the band survives a loaded machine. */
        const val HEADLINE_LOW = 5.0e3
        const val HEADLINE_HIGH = 4.0e4
    }
}
