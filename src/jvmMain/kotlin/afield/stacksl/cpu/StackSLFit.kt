package afield.stacksl.cpu

import afield.stdlib.AD
import afield.stdlib.ADGradient
import afield.stdlib.ADTape
import afield.stdlib.OptimizeMethod
import afield.stdlib.OptimizeOptions
import afield.stdlib.OptimizeRun
import afield.stdlib.minimizeWithGradient
import afield.stdlib.sq
import java.util.stream.IntStream

/**
 * Gradient descent on a StackSL program's own literals: fit a shader to a picture, then write the
 * fitted numbers back into the program.
 *
 * #### Why a StackSL program is differentiable where a RectSL scene is not
 *
 * A shape language is the hard case. RectSL draws shapes, so a pixel's value depends on a
 * parameter through *coverage*, `d(pixel)/d(parameter)` needs a differentiable rasteriser, and a
 * coverage approximation is a modelling choice with a bias to characterise. `RectSLSoftRaster.kt`
 * is that choice, made there.
 *
 * StackSL is not geometry. It is a **per-pixel field**: `main()` runs once per pixel with `fragUV`
 * as its only spatial input, and the output is an ordinary composition of arithmetic and GLSL
 * builtins. So `d(pixel)/d(wire)` is the chain rule. There is no rasteriser in the pipeline, no
 * coverage term, no approximation and so no bias to characterise.
 *
 * #### Three limits on what a fit can do
 *
 *  1. **A fit can only move literal-backed wires.** See [StackSLWires]: a wire noted
 *     `"optional default palette a"` is a default the emitter supplied, with no literal in the
 *     source to rewrite. [StackSLFitResult.fittedIndices] is the population that moved and
 *     [WiredProgram.wires] is the larger set that exists.
 *  2. **The template clamps.** `StackSLCpu.stillTemplate`, the default, ends `main()` with
 *     `outColor = vec4(clamp(c, 0.0, 1.0), 1.0)`, and `clamp` carries zero derivative outside its
 *     range. A pixel that is saturated at the seed therefore contributes **nothing** to the
 *     gradient, so a seed whose image is mostly blown out is a seed with almost no signal.
 *     [StackSLFitResult.saturatedFraction] measures how much of the image is in that state.
 *  3. **The loss is a per-pixel sum of squares and rewards exactly that.** It has no term for
 *     structure, so a fit can lower it a long way by matching a target's colour and luminance
 *     statistics while the image does not resemble the target at all. `StackSLFitFigureTest`
 *     writes a figure of the target, the seed and the fit so the result is looked at and not only
 *     scored.
 */

/**
 * The fit itself: seed program in, fitted program out, with the descent's provenance attached.
 *
 * [fit] is the whole surface; [StackSLFitResult] carries the fitted literals, the loss trajectory
 * and the re-emitted source.
 */
object StackSLFit {

    /** The default fit resolution: 64 square, sized from the measured cost — see `dev_docs`. */
    const val DEFAULT_DIM = 64

    /**
     * Fit [program]'s literals to [target].
     *
     * [target] must be [dim] square. [indices] selects which wires may move; the default is every
     * literal-backed one, which is the set [StackSLWires.rewrite] can write back.
     */
    fun fit(
        program: String,
        target: CpuRaster,
        template: String = StackSLCpu.stillTemplate(),
        dim: Int = DEFAULT_DIM,
        iterations: Int = 60,
        learningRate: Double = 0.05,
        method: OptimizeMethod = OptimizeMethod.ADAM,
        slot: String = "program",
        indices: List<Int>? = null,
        parallel: Boolean = true
    ): StackSLFitResult {
        require(target.width == dim && target.height == dim) {
            "target is ${target.width}x${target.height}, fit is ${dim}x$dim"
        }
        val wired = StackSLWires.lift(program, slot)
        val loss = StackSLImageLoss(wired, template, dim, target, parallel)
        val moving = indices ?: wired.literalWires.map { it.index }
        require(moving.isNotEmpty()) {
            "'$program' has no literal-backed wire to fit: ${wired.describe()}"
        }
        val full = wired.initial
        fun expand(p: DoubleArray): DoubleArray {
            val out = full.copyOf()
            moving.forEachIndexed { i, w -> out[w] = p[i] }
            return out
        }

        val run = minimizeWithGradient(
            DoubleArray(moving.size) { full[moving[it]] },
            OptimizeOptions(
                method = method,
                iterations = iterations,
                learningRate = learningRate,
                showProgress = false
            )
        ) { p ->
            val exact = loss.valueAndGradient(expand(p))
            ADGradient(exact.value, DoubleArray(moving.size) { exact.gradient[moving[it]] })
        }

        val fitted = expand(run.x)
        return StackSLFitResult(
            wired = wired,
            loss = loss,
            fittedIndices = moving,
            fittedWires = fitted,
            run = run,
            fittedProgram = StackSLWires.rewrite(program, wired.wires, fitted)
        )
    }
}

/**
 * A mean-squared-error image loss over a wired program's parameter vector, with both an exact
 * reverse-mode gradient and the plain `Float` render the gradient is checked against.
 *
 * The shader is parsed **once**; a new parameter vector costs one `setGlobal` per wire, because
 * [StackSLWires.lower] turned the wire array into scalar globals. Alpha is excluded from the loss:
 * the gallery template writes a constant `1.0` there, and a constant contributes nothing but
 * dilution.
 */
class StackSLImageLoss(
    val wired: WiredProgram,
    template: String,
    val dim: Int,
    val target: CpuRaster,
    private val parallel: Boolean = true,
    private val outName: String = "outColor",
    private val fragUvName: String = "fragUV"
) {
    /** The complete fragment shader, wires and all — the artifact both paths read. */
    val source: String = StackSLCpu.substituteSlot(template, wired.glsl, wired.slot)

    private val shader = parseGlslShader(source)

    init {
        require(target.width == dim && target.height == dim) {
            "target is ${target.width}x${target.height}, loss is ${dim}x$dim"
        }
    }

    /** Render on the `Float` reference path at [values]. */
    fun render(values: DoubleArray): CpuRaster =
        StackSLCpu.renderFragment(source, dim, dim, wired.globals(values), parallel = parallel)

    /** The loss at [values], via the `Float` path — the finite-difference oracle's evaluator. */
    fun value(values: DoubleArray): Double = meanSquaredError(render(values), target)

    /**
     * The loss and its **exact** gradient at [values], by reverse-mode AD through the same parse
     * tree the `Float` path walks.
     *
     * One [ADTape] per pixel: the loss is a sum over pixels, so the gradient is a sum of per-pixel
     * backward sweeps, and no tape outlives the pixel that built it. Rows run in parallel into
     * private accumulators, so the sum is over a fixed partition and does not depend on thread
     * scheduling — the answer is the same number every time, which a test is entitled to assume.
     */
    fun valueAndGradient(values: DoubleArray): ADGradient {
        val n = wired.wires.size
        require(values.size == n) { "expected $n wire values, got ${values.size}" }
        val perRow = arrayOfNulls<DoubleArray>(dim)
        val rows = IntStream.range(0, dim)
        val stream = if (parallel) rows.parallel() else rows
        stream.forEach { y ->
            val acc = DoubleArray(n + 1)
            for (x in 0 until dim) {
                val tape = ADTape()
                val scalars = GlslAdScalars(tape)
                val wireNodes = Array(n) { scalars.variable(values[it]) }
                val globals = HashMap<String, AdValue>(n + 1)
                for (k in 0 until n) {
                    globals[StackSLWires.globalName(wired.prefix, k)] =
                        AdValue(GlslType.FLOAT, arrayOf(wireNodes[k]))
                }
                globals[fragUvName] = scalars.lift(StackSLCpu.fragUvAt(x, y, dim, dim))
                val machine = shader.newMachine(scalars, globals, GlslSamplers.REFUSE)
                if (!machine.runMain()) {
                    throw GlslEvalError("pixel ($x, $y) executed discard; a fit has no answer for that")
                }
                val out = machine.global(outName) ?: throw GlslEvalError("shader never wrote '$outName'")
                var pixel: AD = tape.constant(0.0)
                for (ch in 0 until CHANNELS) {
                    pixel += (out.c[ch] - target.at(x, y, ch).toDouble()).sq()
                }
                val adjoint = tape.backward(pixel)
                acc[0] += pixel.value
                for (k in 0 until n) acc[k + 1] += adjoint[wireNodes[k].index]
            }
            perRow[y] = acc
        }
        val scale = 1.0 / (dim.toDouble() * dim.toDouble() * CHANNELS)
        var total = 0.0
        val gradient = DoubleArray(n)
        perRow.forEach { row ->
            val r = row!!
            total += r[0]
            for (k in 0 until n) gradient[k] += r[k + 1]
        }
        for (k in 0 until n) gradient[k] *= scale
        return ADGradient(total * scale, gradient)
    }

    /**
     * The primal image the differentiable pass computed, as a raster.
     *
     * Not a convenience: this is the evidence that the AD pass is evaluating the *same* shader.
     * Every node [GlslAdScalars] pushes rounds its value to `Float` where the `Float` path rounds,
     * so this raster is expected to be **bit-identical** to [render]'s, and
     * `StackSLGradientTest` asserts exactly that over a named population.
     */
    fun renderPrimal(values: DoubleArray): CpuRaster {
        val n = wired.wires.size
        val rgba = FloatArray(dim * dim * 4)
        for (y in 0 until dim) {
            val tape = ADTape()
            val scalars = GlslAdScalars(tape)
            val globals = HashMap<String, AdValue>(n + 1)
            for (k in 0 until n) {
                globals[StackSLWires.globalName(wired.prefix, k)] =
                    AdValue(GlslType.FLOAT, arrayOf(scalars.constant(values[k])))
            }
            for (x in 0 until dim) {
                globals[fragUvName] = scalars.lift(StackSLCpu.fragUvAt(x, y, dim, dim))
                val machine = shader.newMachine(scalars, globals, GlslSamplers.REFUSE)
                machine.runMain()
                val out = machine.global(outName)!!.primal()
                val base = (y * dim + x) * 4
                for (ch in 0 until minOf(4, out.c.size)) rgba[base + ch] = out.c[ch]
            }
        }
        return CpuRaster(dim, dim, rgba)
    }

    /** Fraction of RGB samples that sit exactly on a clamp bound, and so carry no gradient. */
    fun saturatedFraction(values: DoubleArray): Double {
        val raster = render(values)
        var saturated = 0
        for (i in 0 until dim * dim) for (ch in 0 until CHANNELS) {
            val v = raster.rgba[i * 4 + ch]
            if (v <= 0f || v >= 1f) saturated++
        }
        return saturated.toDouble() / (dim.toDouble() * dim * CHANNELS)
    }

    companion object {
        /** RGB. Alpha is a constant in every gallery template, so comparing it is decoration. */
        const val CHANNELS = 3

        /** Mean squared error over the three colour channels of every pixel. */
        fun meanSquaredError(a: CpuRaster, b: CpuRaster): Double {
            require(a.width == b.width && a.height == b.height) { "raster sizes differ" }
            var sum = 0.0
            for (i in 0 until a.width * a.height) for (ch in 0 until CHANNELS) {
                val d = (a.rgba[i * 4 + ch] - b.rgba[i * 4 + ch]).toDouble()
                sum += d * d
            }
            return sum / (a.width.toDouble() * a.height * CHANNELS)
        }
    }
}

/** What a fit produced, including the program a human can paste back into a box. */
class StackSLFitResult(
    /** The wired program that was fitted. */
    val wired: WiredProgram,
    /** The loss the descent ran on — keep it to render the seed and the fit at the same size. */
    val loss: StackSLImageLoss,
    /** The wire indices that were allowed to move, in the optimiser's own order. */
    val fittedIndices: List<Int>,
    /** The whole wire vector after the fit, indexed by wire index. */
    val fittedWires: DoubleArray,
    /** The descent's provenance: losses, trajectory, gradient norms. */
    val run: OptimizeRun,
    /** [wired]'s source with the fitted literals substituted at their own token positions. */
    val fittedProgram: String
) {
    /** The wire vector the fit started from. */
    val seedWires: DoubleArray get() = wired.initial

    /** The loss before any step. */
    val seedLoss: Double get() = run.initialValue

    /** The loss at the end. */
    val fittedLoss: Double get() = run.value

    /** How far the loss fell, as a ratio. 1.0 means the descent achieved nothing. */
    val lossRatio: Double get() = if (run.value == 0.0) Double.POSITIVE_INFINITY else run.initialValue / run.value

    /** Fraction of RGB samples clamped at the seed — see [StackSLFit]'s second caveat. */
    val saturatedFraction: Double get() = loss.saturatedFraction(seedWires)

    /** One line per moved literal: what the human wrote, and what the fit made of it. */
    fun report(): List<String> = fittedIndices.map { w ->
        val wire = wired.wires[w]
        "wire #$w  '${wire.token}' at ${wire.line}:${wire.column}  " +
            "${wire.initial} -> ${StackSLWires.formatLiteral(fittedWires[w])}"
    }
}
