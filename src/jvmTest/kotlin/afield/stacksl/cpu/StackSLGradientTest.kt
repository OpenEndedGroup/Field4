package afield.stacksl.cpu

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The differentiable StackSL path against two independent oracles.
 *
 * **Oracle one: the `Float` path itself.** Every node [GlslAdScalars] pushes rounds its value to
 * `Float` where the `Float` path rounds, so the differentiable pass's *primal* is supposed to be
 * the *same image* the reference path renders — not close to it, identical to it. That is asserted
 * bit-for-bit over the whole population, and it is what makes the second oracle meaningful: a
 * finite difference of a different function is not a finite difference.
 *
 * **Oracle two: central differences on the `Float` path.** Exact reverse-mode gradient against
 * `(L(v+h) - L(v-h)) / 2h`, per wire, over a named population, with floored assertions, counted
 * exclusions, and a counterfactual — because a test that cannot fail is not a guard.
 *
 * #### Where the precision actually goes: `clamp`, and the first guess about it was wrong
 *
 * A central difference *across a kink* reports a slope the exact gradient does not have, so a
 * program that clamps has a worse oracle than one that does not — and the clamped pixels' density
 * decides how much worse. The first guess was that the **gallery template** was to blame, since it
 * ends `outColor = vec4(clamp(c, 0.0, 1.0), 1.0)`. Measured: removing that clamp left the worst
 * disagreement **unchanged to four digits**, because the worst program ends in `saturate` and had
 * already clamped itself. The clamp that matters is whichever one is *active*, and for five of
 * these nine programs it is one the program asked for.
 *
 * So the split here is by **measurement, not by keyword**: a program is *kinked* when more than
 * [KINK_THRESHOLD] of its RGB samples sit exactly on 0 or 1 in an unclamped render. That is a
 * property of the image the program draws, so it cannot go stale the way a list of word names
 * would. Kinked programs are held to [KINKED_TOLERANCE] and smooth ones to [SMOOTH_TOLERANCE],
 * and the kinked bound is asserted **two-sided** — worse than the smooth one — because "the clamp
 * costs precision" is a claim and a one-sided bound could not tell a real kink from a lucky one.
 *
 * #### The wires with no oracle, and the second expectation that was wrong
 *
 * A wire whose exact gradient is **exactly zero** has no finite-difference oracle at all: a
 * central difference across a jump returns a spike of order `1/h` where the exact route correctly
 * returns 0. That is `Fn`'s recorded trap, and it is by design. Excluded, and counted. In this
 * population they come from two different places and both are real:
 *
 *  - `threshold` lowers to a hard `step`, so *everything upstream of it* is derivative-free —
 *    including the noise frequency. A program that thresholds is not fittable above the threshold.
 *  - the `w` component of a `palette` or `grade` operand never reaches RGB, so it is structurally
 *    unused rather than non-differentiable. The measurement cannot tell those apart and does not
 *    claim to; what it reports is that the gradient is exactly zero, which for a fit is the same
 *    fact.
 *
 * The other wrong expectation: `fbm`'s lattice runs through `_hash21`, and the chain rule through
 * a `fract(<large> · …)` is enormous, so a noise frequency was predicted to be hash-amplified past
 * any oracle — [CHAOS_LIMIT] is the rule that would have caught it. Measured: **0 of 46 wires are
 * hash-amplified**, and `uv 3 * fbm`'s frequency agrees with a central difference to 1.5e-5. The
 * reason is `floor`: the hash is evaluated at the lattice **cell index**, which carries exactly
 * zero derivative, so no gradient flows through the hash at all — only through `fract`'s
 * interpolation weight, which is smooth. `floor` is what makes a noise frequency differentiable,
 * which is the opposite of what it does to a `threshold`.
 */
class StackSLGradientTest {

    /**
     * The population: nine programs spanning smooth arithmetic, the transcendentals, a cosine
     * palette, a matrix warp, two shapers, a colour-space round trip, a hard threshold and `fbm`'s
     * hash lattice — 46 literal-backed wires between them.
     */
    private val population = listOf(
        "uv 3.0 * sin 0.5 + palette",
        "uv 2.0 * sin 0.25 * 0.5 + saturate",
        "uv 1.5 * sin 8.0 sigmoid palette",
        "uv 3.0 * sin 0.2 0.8 ramp palette",
        "p 1.7 twist 2.0 * sin palette saturate",
        "uv 0.5 + ryb saturate",
        "uv 2.0 * sin 0.45 threshold orange 0.8 * saturate",
        "uv 3 * fbm\n0.5 0.5 0.5 1 vec4\n0.5 0.5 0.5 1 vec4\n1.0 1.0 1.0 1 vec4\n0.0 0.33 0.67 1 vec4\npalette",
        "uv 6.0 * fbm palette\n0.02 0.00 -0.01 0.0 vec4\n1.15 1.05 0.95 1.0 vec4\n0.85 0.95 1.10 1.0 vec4\ngrade\nsaturate"
    )

    /**
     * The gallery still template with its own `clamp` removed, so the only kinks left are the
     * ones the *programs* contain.
     *
     * Derived from [StackSLCpu.stillTemplate] rather than copied, and the derivation is asserted:
     * if the template stops containing that exact expression this test says so instead of quietly
     * comparing against the clamped one.
     */
    private fun unclampedTemplate(): String {
        val shipping = StackSLCpu.stillTemplate()
        val clamped = "clamp(c, 0.0, 1.0)"
        assertTrue(shipping.contains(clamped), "the still template no longer contains '$clamped'")
        return shipping.replace(clamped, "c")
    }

    /** A fixed, program-independent target, so the loss is a real objective and not zero. */
    private fun target(dim: Int): CpuRaster = CpuRaster(
        dim, dim,
        FloatArray(dim * dim * 4) { i ->
            val pixel = i / 4
            val x = (pixel % dim).toFloat() / dim
            val y = (pixel / dim).toFloat() / dim
            when (i % 4) {
                0 -> 0.5f * x + 0.25f
                1 -> 0.5f * y + 0.2f
                2 -> 0.4f * (1f - x) + 0.3f
                else -> 1f
            }
        }
    )

    /** Fraction of RGB samples pinned exactly to 0 or 1 — a clamp that is *active*. */
    private fun pinnedFraction(raster: CpuRaster): Double {
        var pinned = 0
        val pixels = raster.width * raster.height
        for (i in 0 until pixels) for (ch in 0 until StackSLImageLoss.CHANNELS) {
            val v = raster.rgba[i * 4 + ch]
            if (v == 0f || v == 1f) pinned++
        }
        return pinned.toDouble() / (pixels.toDouble() * StackSLImageLoss.CHANNELS)
    }

    @Test
    fun `the differentiable pass computes the same image as the Float path, bit for bit`() {
        var checked = 0
        var floats = 0
        population.forEach { program ->
            val wired = StackSLWires.lift(program)
            val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), PRIMAL_DIM, target(PRIMAL_DIM))
            val values = wired.initial
            val reference = loss.render(values)
            val primal = loss.renderPrimal(values)
            val differing = reference.rgba.indices.count {
                reference.rgba[it].toRawBits() != primal.rgba[it].toRawBits()
            }
            assertTrue(
                differing == 0,
                "the AD primal differs from the Float path in $differing of ${reference.rgba.size} " +
                    "floats for '${program.lines().first()}' — the two are supposed to be the same shader"
            )
            checked++
            floats += reference.rgba.size
        }
        println("AD primal == Float path: $checked programs, $floats floats, 0 differing bits")
        assertTrue(checked == population.size, "checked $checked of ${population.size}")
        assertTrue(floats >= 9 * PRIMAL_DIM * PRIMAL_DIM * 4, "population shrank")
    }

    @Test
    fun `exact gradients agree with central differences, tightly where nothing clamps`() {
        val report = compare(unclampedTemplate())
        report.lines.forEach { println(it) }
        println("SMOOTH  " + report.smooth.summary("smooth"))
        println("KINKED  " + report.kinked.summary("kinked"))

        assertTrue(report.programs == population.size, "only ${report.programs} programs produced a gradient")
        assertTrue(
            report.wires >= MIN_WIRES,
            "population has ${report.wires} literal-backed wires, expected at least $MIN_WIRES"
        )
        assertTrue(
            report.zeroGradient in MIN_ZERO..MAX_ZERO,
            "expected $MIN_ZERO..$MAX_ZERO exactly-zero-gradient wires, found ${report.zeroGradient} — " +
                "the emitter may have changed what `threshold` or a `palette` operand's `w` does"
        )
        assertTrue(
            report.chaotic == 0,
            "${report.chaotic} wires exceeded the hash-amplification limit $CHAOS_LIMIT; the measured " +
                "answer at this population is 0, because fbm hashes a floor()ed cell index"
        )

        // The smooth half: this is where the derivative rules are actually being checked.
        assertTrue(
            report.smooth.count >= MIN_SMOOTH,
            "only ${report.smooth.count} wires belong to a program that does not clamp, expected at " +
                "least $MIN_SMOOTH — a classification that empties the tight class is not a guard"
        )
        assertTrue(
            report.smooth.worst <= SMOOTH_TOLERANCE,
            "worst disagreement on an unclamped program was ${"%.3e".format(report.smooth.worst)} at " +
                "${report.smooth.worstWhere}, over ${report.smooth.count} wires; floor is $SMOOTH_TOLERANCE"
        )
        assertTrue(
            report.smooth.worst >= SMOOTH_TOLERANCE / 20.0,
            "worst smooth disagreement ${"%.3e".format(report.smooth.worst)} is more than 20x under " +
                "$SMOOTH_TOLERANCE — tighten the floor rather than leaving it unable to notice"
        )

        // The kinked half, two-sided: the claim is that an active clamp costs precision.
        assertTrue(
            report.kinked.count >= MIN_KINKED,
            "only ${report.kinked.count} wires belong to a clamping program, expected at least $MIN_KINKED"
        )
        assertTrue(
            report.kinked.worst <= KINKED_TOLERANCE,
            "worst disagreement on a clamping program was ${"%.3e".format(report.kinked.worst)} at " +
                "${report.kinked.worstWhere}; floor is $KINKED_TOLERANCE"
        )
        assertTrue(
            report.kinked.worst > SMOOTH_TOLERANCE,
            "a clamping program disagreed by only ${"%.3e".format(report.kinked.worst)}, inside the " +
                "smooth floor $SMOOTH_TOLERANCE — then the clamp is not the explanation and this " +
                "test's story is wrong"
        )
    }

    @Test
    fun `a corrupted gradient fails the same check`() {
        val report = compare(unclampedTemplate(), corrupt = true)
        println("CORRUPT " + report.smooth.summary("smooth") + " | " + report.kinked.summary("kinked"))
        assertTrue(
            report.smooth.count >= MIN_SMOOTH,
            "the counterfactual must compare the same wires: ${report.smooth.count} vs $MIN_SMOOTH"
        )
        assertTrue(
            report.smooth.worst > KINKED_TOLERANCE,
            "a gradient scaled by 1.5 with one sign flipped still agreed to " +
                "${"%.3e".format(report.smooth.worst)}, inside even the kinked floor " +
                "$KINKED_TOLERANCE — the comparison above cannot fail"
        )
    }

    // -- measurement -------------------------------------------------------------------------------

    private class Bucket {
        var count = 0
        var worst = 0.0
        var worstWhere = "-"

        fun observe(relative: Double, where: String) {
            count++
            if (relative > worst) { worst = relative; worstWhere = where }
        }

        fun summary(name: String): String =
            "$count $name wires, worst relative disagreement ${"%.3e".format(worst)} at $worstWhere"
    }

    private class Report {
        var programs = 0
        var wires = 0
        var zeroGradient = 0
        var chaotic = 0
        val smooth = Bucket()
        val kinked = Bucket()
        val lines = ArrayList<String>()
    }

    /**
     * Exact gradient versus central differences for every literal-backed wire in the population,
     * through [template], bucketed by whether the program's own image clamps.
     *
     * With [corrupt], the exact gradient is scaled by 1.5 and one component per program is sign
     * flipped before the comparison — the counterfactual that proves the check can fail.
     */
    private fun compare(template: String, corrupt: Boolean = false): Report {
        val report = Report()
        val target = target(DIM)
        population.forEach { program ->
            val wired = StackSLWires.lift(program)
            val loss = StackSLImageLoss(wired, template, DIM, target)
            val base = wired.initial
            val pinned = pinnedFraction(loss.render(base))
            val kinked = pinned > KINK_THRESHOLD
            val exact = loss.valueAndGradient(base).gradient
            var flipped = false
            val label = program.lines().first().take(40)
            report.programs++
            wired.literalWires.forEach { wire ->
                val w = wire.index
                report.wires++
                var g = exact[w]
                val h = FD_STEP * max(1.0, abs(base[w]))
                val up = base.copyOf().also { it[w] += h }
                val down = base.copyOf().also { it[w] -= h }
                val fd = (loss.value(up) - loss.value(down)) / (2.0 * h)
                val category = when {
                    g == 0.0 -> "zero"
                    abs(g) > CHAOS_LIMIT -> "chaotic"
                    kinked -> "kinked"
                    else -> "smooth"
                }
                if (corrupt && (category == "smooth" || category == "kinked")) {
                    g *= 1.5
                    if (!flipped) { g = -g; flipped = true }
                }
                val relative = abs(g - fd) / max(1.0, abs(g))
                val where = "'$label' wire #$w '${wire.token}'"
                when (category) {
                    "zero" -> report.zeroGradient++
                    "chaotic" -> report.chaotic++
                    "kinked" -> report.kinked.observe(relative, where)
                    else -> report.smooth.observe(relative, where)
                }
                report.lines += "%-42s #%-2d %-8s pin %.3f  exact %+.6e  fd %+.6e  rel %.2e  %s"
                    .format(label, w, wire.token, pinned, g, fd, relative, category)
            }
        }
        return report
    }

    private companion object {
        /** 48 square for the gradient comparison: `2n+1` loss evaluations per wire is the cost. */
        const val DIM = 48

        /** 24 square for the primal check, which needs coverage rather than resolution. */
        const val PRIMAL_DIM = 24

        /**
         * The central-difference step, relative to the wire's own magnitude.
         *
         * Sized against the `Float` path's resolution: a pixel is known to ~1e-7, the loss averages
         * 6912 squares, and a compared gradient is of order 1e-2, so `h = 1e-3` moves the loss by
         * about 1e-5 — two orders above that floor, and small enough that the truncation term stays
         * under the tolerances below.
         */
        const val FD_STEP = 1e-3

        /** Above this, a gradient came through a hash and no finite difference is its oracle. */
        const val CHAOS_LIMIT = 1e2

        /** More than a tenth of a percent of samples pinned to 0 or 1 makes a program *kinked*. */
        const val KINK_THRESHOLD = 1e-3

        /** Measured worst case where nothing clamps is 1.5e-5 (the `fbm` frequency wire). */
        const val SMOOTH_TOLERANCE = 5e-5

        /** Measured worst case where the program clamps is 9.4e-3 — 600x the smooth case. */
        const val KINKED_TOLERANCE = 2e-2

        const val MIN_WIRES = 46
        const val MIN_SMOOTH = 20
        const val MIN_KINKED = 14

        /** 2 above a `threshold`, the rest unused `w` components of `palette`/`grade` operands. */
        const val MIN_ZERO = 8
        const val MAX_ZERO = 11
    }
}
