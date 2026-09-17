package afield.rectsl

import afield.stdlib.AD
import afield.stdlib.ADTape
import afield.stdlib.FnAutoDiff
import afield.stdlib.Functions
import afield.stdlib.abs
import afield.stdlib.clamp
import afield.stdlib.cos
import afield.stdlib.extractParameters
import afield.stdlib.hypot
import afield.stdlib.max
import afield.stdlib.min
import afield.stdlib.sigmoid
import afield.stdlib.sin

/**
 * The third lowering: a RectSL scene as a **differentiable raster**, so a picture can be fitted.
 *
 * # Why an image fit needs a rasterizer
 *
 * [Track.fitParameters] turns the arrow round for one *track*: mark a number `free-param`, say what
 * the track should read at three instants, and the exact gradient of the lowered `Fn` walks the
 * number to the answer. What it cannot do is take a *target image*, because the derivative an image
 * fit needs is `d(pixel)/d(parameter)` and a per-track scalar lowering has no pixels in it. That was
 * recognised on 2026-08-06 and the image fit was abandoned rather than half-landed. This file is the
 * retry, on the terms the abandonment set: a soft rasterizer **whose coverage bias is measured**.
 * The exact reference that bias is measured against is [exactCoverage]; the numbers, and where they
 * say this is and is not usable, are in `dev_docs/RECTSL_SOFT_RASTER.md`.
 *
 * # The one design decision worth arguing about
 *
 * The obvious plan is "lower the scene to an `Fn`-valued signed distance field over (x, y, t)".
 * [Functions.Fn] cannot be that: it is a `(Double) -> Double`, one input and one output, and every
 * existing consumer of a lowered track — [freeParameterSlots], [Track.substituteParameters],
 * [Track.fitParameters] — reads its θ-vector as *the track's own leaves*. Smuggling x and y in as two
 * more θ leaves would make all of them quietly wrong about what they are solving for.
 *
 * So the differentiable layer here is not `Fn`, it is [AD] on an [ADTape] — which is what `Fn`'s own
 * gradient is built out of anyway. [FnAutoDiff.interpret] already re-reads an `Fn` graph in `AD`
 * arithmetic at an `AD`-tracked x, so:
 *
 * - every **track** goes through the existing lowering, [Track.toFn], unchanged;
 * - every track is then *interpreted* on the tape at the frame's time;
 * - the **geometry** — the ancestor-group composition, the rotate-into-local-frame, the exact box
 *   field, the `min` union — is written in `AD` directly, transcribed from the GLSL in
 *   [RectSLGlsl.PRELUDE] and the composition in [Transform2d.then];
 * - the **coverage kernel** and the **image loss** go on the same tape.
 *
 * One backward sweep then yields `d(loss)/dθ` for every parameter in the scene at once, with nothing
 * finite-differenced anywhere in the chain. That is strictly more than an `Fn` could have given.
 *
 * # The scene-level θ ledger
 *
 * [Track.fitParameters] masks θ within **one** `Fn`. A scene has eight tracks per rect and 183 rects
 * in the largest gallery scene, so it has hundreds of independent θ-vectors, and "index *i*" means
 * nothing until they are concatenated. [SceneAdField] does that concatenation once: every channel
 * gets a contiguous block, [SceneAdField.theta] is the whole scene's leaves in block order, and
 * [SceneAdField.freeParameterSlots] re-maps each `free-param` **name** to its *global* slots —
 * unioned across channels, because one name legitimately reaches several of them. The
 * gradient-summed, step-applied-to-every-slot discipline of [Track.fitParameters] carries over
 * unchanged; only the index space is new.
 *
 * # Why a frame is a separate object
 *
 * [ADTape] records five parallel arrays per operation and never shrinks. A 128² raster over a
 * 30-rect scene, naively taped, is ~14M nodes — half a gigabyte. But a shape's *transform* depends
 * only on (θ, t) and not on the pixel, so [SceneAdField.frameAt] evaluates all of it once and
 * [SceneAdFrame] hands back only what varies per pixel: centre, rotation, half-extent, alpha. The
 * per-pixel cost drops to about twenty tape nodes per rect, and because an image loss is a *sum*
 * over pixels its gradient is additive — so [softRasterLossAndGradient] walks the image in row
 * chunks, one tape per chunk, and accumulates. Memory becomes a choice rather than a limit.
 *
 * # Fidelity, and the honesty check that is not optional
 *
 * A second interpreter of the same graph is exactly the situation where a *slightly different
 * function* gets differentiated confidently. `autodiff_fn.kt` has a name for the guard against that
 * — [FnAutoDiff.worstValueDisagreement] — and [toAdField] runs it on every channel it lowers,
 * recording the worst in [SceneAdField.worstChannelDisagreement]. A channel whose `AD` reading parts
 * company with its own `Fn.eval` is refused, not averaged in.
 */

/** The sentinel an invisible rect's distance returns, matching the emitted GLSL's `1e9` exactly. */
const val RECTSL_INVISIBLE_DISTANCE: Double = 1e9

/** How far apart the `AD` reading of a channel and its own `Fn.eval` may be before it is refused. */
const val RECTSL_CHANNEL_TOLERANCE: Double = 1e-9

/** Instants a channel's `AD` reading is checked against `Fn.eval` at, as fractions of the window. */
private val CHANNEL_CHECK_FRACTIONS: DoubleArray =
    doubleArrayOf(0.0, 0.13, 0.31, 0.5, 0.67, 0.89, 1.0)

/**
 * One channel of one transform level, lowered, with its block of the scene's θ.
 *
 * [indices] and [size] are computed once at lowering time because both are graph walks and both
 * would otherwise happen per pixel.
 */
internal class LoweredChannel(
    val fn: Functions.Fn,
    val offset: Int,
    val size: Int,
    val indices: Map<List<Int>, Int>
) {
    /** This channel's value at [t], tracked, reading its own slice of the scene θ. */
    fun at(t: AD, theta: List<AD>): AD =
        FnAutoDiff.interpret(fn, t, theta.subList(offset, offset + size), indices, emptyList())
}

/** One level of a rect's transform stack: a group on the way down, or the rect itself. */
internal class LoweredLevel(
    val x: LoweredChannel,
    val y: LoweredChannel,
    val rot: LoweredChannel,
    val scaleX: LoweredChannel,
    val scaleY: LoweredChannel,
    val alpha: LoweredChannel
)

/**
 * One rect, with its ancestor groups flattened into an ordered transform stack.
 *
 * Outermost group first and the rect last, which is the order [Transform2d.then] composes in.
 */
class AdShape internal constructor(
    /** The [RectNode.id] this came from, so a disagreement can be attributed to a rect. */
    val id: String,
    internal val levels: List<LoweredLevel>,
    internal val w: LoweredChannel,
    internal val h: LoweredChannel
)

/**
 * A rect's world transform at one instant, on one tape — the part that does not vary per pixel.
 *
 * [halfX] and [halfY] already carry the accumulated scale and [alpha] is already clamped to `0..1`,
 * both matching the `SampledRect` [SceneProgram.sample] would have produced.
 */
class AdShapeFrame internal constructor(
    /** The rect this is a frame of. */
    val id: String,
    /** World-space centre x, tracked. */
    val centreX: AD,
    /** World-space centre y, tracked. */
    val centreY: AD,
    /** Accumulated rotation in radians, tracked. */
    val rotation: AD,
    /** Half-width including accumulated scale, tracked and non-negative. */
    val halfX: AD,
    /** Half-height including accumulated scale, tracked and non-negative. */
    val halfY: AD,
    /** Accumulated alpha, clamped to `0..1`, tracked. */
    val alpha: AD
) {
    /** True when this rect contributes a silhouette at all — the emitted shader's own test. */
    val visible: Boolean get() = alpha.value > 0.0

    /**
     * The exact signed distance from `(x, y)` to this rect, tracked: positive outside, negative
     * inside, transcribed from `rectslRect` / `rectslBox` in [RectSLGlsl.PRELUDE].
     */
    fun distanceAt(x: Double, y: Double): AD {
        val qx = -centreX + x
        val qy = -centreY + y
        val c = cos(rotation)
        val s = sin(rotation)
        val localX = c * qx + s * qy
        val localY = -(s * qx) + c * qy
        val dx = abs(localX) - halfX
        val dy = abs(localY) - halfY
        return hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0)
    }
}

/**
 * Every rect of a scene, transformed at one instant, on one tape.
 *
 * Hold one of these for as long as a tape is worth reusing — a row chunk of a raster, or a batch of
 * probe points — and no longer: the tape it belongs to grows monotonically.
 */
class SceneAdFrame internal constructor(
    /** The tape every value in this frame lives on. */
    val tape: ADTape,
    /** The scene's whole θ-vector as tape variables, in [SceneAdField.theta] order. */
    val theta: List<AD>,
    /** The instant this frame was transformed at. */
    val time: Double,
    /** Every rect, in scene order, visible or not. */
    val shapes: List<AdShapeFrame>
) {
    /** The rects not gated invisible at this instant. */
    val visible: List<AdShapeFrame> by lazy(LazyThreadSafetyMode.NONE) { shapes.filter { it.visible } }

    /**
     * The scene's signed distance at `(x, y)`: the `min` over visible rects, or
     * [RECTSL_INVISIBLE_DISTANCE] when nothing is visible.
     *
     * `min` is the union's distance **outside** the union and an under-estimate of it *inside*, where
     * the nearest individual boundary can be much closer than the union's own. That is not a defect
     * of this transcription — the emitted GLSL does the same thing — but it is one of the three bias
     * sources the docs page measures, and the one that bites hardest.
     */
    fun distanceAt(x: Double, y: Double): AD {
        val vis = visible
        if (vis.isEmpty()) return tape.constant(RECTSL_INVISIBLE_DISTANCE)
        var d = vis[0].distanceAt(x, y)
        for (i in 1 until vis.size) d = min(d, vis[i].distanceAt(x, y))
        return d
    }
}

/**
 * A whole scene, lowered: its shapes, its concatenated θ, and what it had to leave out.
 *
 * Partial by design. A few percent of the gallery's tracks still refuse to lower (`atan2`, and the
 * pattern queries, which are an interpreter rather than a formula), and a scene containing one is
 * still worth differentiating everywhere else. What is *not* acceptable is doing that silently, so
 * [skipped] carries the rect ids and the reasons out, exactly as [TrackFitResult.stuckParameters]
 * does for a partly stuck fit.
 */
class SceneAdField internal constructor(
    /** The scene this was lowered from. */
    val program: SceneProgram,
    /** The rects that lowered, in scene order. */
    val shapes: List<AdShape>,
    /** Every numeric leaf of every lowered channel, in channel-block order. */
    val theta: DoubleArray,
    /** Each `free-param` name's **global** θ slots, unioned across the channels that use it. */
    val freeParameterSlots: Map<String, List<Int>>,
    /** Rect id to refusal reason, for the rects that could not be lowered. */
    val skipped: Map<String, String>,
    /** The worst `AD`-versus-`Fn.eval` disagreement across every channel that was accepted. */
    val worstChannelDisagreement: Double,
    /** The time window every channel was lowered over. */
    val window: Functions.Domain
) {
    /** How many rects lowered. */
    val rectCount: Int get() = shapes.size

    /** The scene's free-parameter names in first-seen order. */
    val freeParameterNames: List<String> get() = freeParameterSlots.keys.toList()

    /**
     * Transforms every shape at [time] on a fresh tape, with [at] as the θ-vector.
     *
     * @param at defaults to this field's own θ. Must be [theta]-length; the check is code rather than
     *        a comment because a short vector would silently leave later channels at their seed
     *        values, which reads as a converged fit.
     */
    fun frameAt(time: Double, at: DoubleArray = theta): SceneAdFrame {
        require(at.size == theta.size) {
            "theta has ${at.size} entries but this scene has ${theta.size} numeric leaves"
        }
        val tape = ADTape()
        val vars = tape.variables(at)
        val t = tape.constant(time)
        val frames = shapes.map { shape ->
            var cx = tape.constant(0.0)
            var cy = tape.constant(0.0)
            var rot = tape.constant(0.0)
            var sx = tape.constant(1.0)
            var sy = tape.constant(1.0)
            var alpha = tape.constant(1.0)
            // Transform2d.then, verbatim: a child's translation is scaled by the parent's scale,
            // rotated by the parent's rotation, and added; rotations, scales and alphas accumulate.
            // Written out rather than folded so the correspondence stays readable.
            for (level in shape.levels) {
                val ox = level.x.at(t, vars) * sx
                val oy = level.y.at(t, vars) * sy
                val c = cos(rot)
                val s = sin(rot)
                cx += c * ox - s * oy
                cy += s * ox + c * oy
                rot += level.rot.at(t, vars)
                sx *= level.scaleX.at(t, vars)
                sy *= level.scaleY.at(t, vars)
                alpha *= level.alpha.at(t, vars)
            }
            AdShapeFrame(
                id = shape.id,
                centreX = cx,
                centreY = cy,
                rotation = rot,
                halfX = abs(shape.w.at(t, vars) * sx * 0.5),
                halfY = abs(shape.h.at(t, vars) * sy * 0.5),
                alpha = alpha.clamp(0.0, 1.0)
            )
        }
        return SceneAdFrame(tape, vars, time, frames)
    }

    /**
     * This scene's signed distance at one point and instant, untracked — the shape of call the
     * differential check against [SceneProgram.sample] makes.
     *
     * Costs a whole tape, so it is the wrong call inside a loop; [frameAt] then
     * [SceneAdFrame.distanceAt] is the right one. Kept because a one-point answer is what a reader,
     * a test and a REPL all reach for first.
     */
    fun signedDistanceAt(x: Double, y: Double, time: Double, at: DoubleArray = theta): Double =
        frameAt(time, at).distanceAt(x, y).value

    /** The value of each named free parameter in [at], read from its first global slot. */
    fun freeParameters(at: DoubleArray = theta): Map<String, Double> =
        freeParameterSlots.mapValues { (_, slots) -> at[slots.first()] }

    /**
     * A copy of [at] with each name in [values] written to **every** slot it occupies.
     *
     * Every slot, for the reason [freeParameterSlots] gives: a lowering may reach one sub-`Fn` more
     * than once, and moving some occurrences of a variable and not others produces a graph that
     * corresponds to no scene.
     */
    fun withFreeParameters(values: Map<String, Double>, at: DoubleArray = theta): DoubleArray {
        val out = at.copyOf()
        for ((name, value) in values) {
            val slots = freeParameterSlots[name]
                ?: throw RectLangException(
                    "no free parameter named '$name' in this scene; it has " +
                        freeParameterNames.ifEmpty { listOf("none") }
                )
            for (slot in slots) out[slot] = value
        }
        return out
    }
}

/**
 * The three soft-coverage kernels, **normalised to be comparable**: each reads 0.5 at the surface
 * and has slope `1/beta` there.
 *
 * The normalisation is the whole reason a bias comparison between them means anything. Without it a
 * sigmoid with a free temperature can be made to look like any of the others, and "kernel A is more
 * biased than kernel B" would be a statement about two different band widths.
 *
 * They differ in exactly one property besides bias, and it is the property that matters to a fit:
 * **reach**. [LINEAR] and [SMOOTHSTEP] are identically zero beyond `beta/2` and so hand a fit no
 * gradient at all from a pixel further than that from an edge; [SIGMOID] never reaches zero. The
 * measured trade-off is in `dev_docs/RECTSL_SOFT_RASTER.md`.
 */
enum class CoverageKernel {
    /**
     * The clamped ramp `clamp(0.5 - d/beta, 0, 1)`.
     *
     * At `beta` = one pixel this is not an approximation at all for an axis-aligned edge: the exact
     * area of a unit square on one side of an axis-aligned line **is** `clamp(0.5 - d)`.
     * `RectSLSoftRasterTest` asserts that the measured bias there is at machine precision, which
     * doubles as the premise check for the exact-coverage reference.
     */
    LINEAR,

    /** The ramp put through `3u² - 2u³`, so coverage is C¹ at the band edges instead of kinked. */
    SMOOTHSTEP,

    /**
     * `sigmoid(-4 d / beta)` — the factor 4 is what makes its slope at the surface `1/beta`, matching
     * the other two.
     */
    SIGMOID;

    /** This kernel's coverage for a tracked signed distance [d] at band width [beta]. */
    fun coverage(d: AD, beta: Double): AD = when (this) {
        LINEAR -> (-(d / beta) + 0.5).clamp(0.0, 1.0)
        SMOOTHSTEP -> {
            val u = (-(d / beta) + 0.5).clamp(0.0, 1.0)
            u * u * (-(u * 2.0) + 3.0)
        }
        SIGMOID -> sigmoid(-(d * (4.0 / beta)))
    }

    /** This kernel's coverage for a plain signed distance — the same formula, untracked. */
    fun coverage(d: Double, beta: Double): Double = when (this) {
        LINEAR -> (0.5 - d / beta).coerceIn(0.0, 1.0)
        SMOOTHSTEP -> {
            val u = (0.5 - d / beta).coerceIn(0.0, 1.0)
            u * u * (3.0 - 2.0 * u)
        }
        SIGMOID -> 1.0 / (1.0 + kotlin.math.exp(4.0 * d / beta))
    }
}

/**
 * How per-rect coverage becomes one number per pixel.
 *
 * Two candidates, both defensible, with materially different bias — which is why both are here and
 * both are measured rather than one being chosen in advance.
 */
enum class CoverageComposite {
    /**
     * Coverage of the **union distance**: `kernel(min_i d_i)`.
     *
     * What the emitted GLSL implies, and what any SDF consumer would write. Its weakness is
     * structural: `min` of signed distances is not the union's interior distance, so at a seam
     * between two touching rects the field reads about zero where the union is solidly interior, and
     * coverage collapses towards 0.5 in a region that is fully covered.
     */
    UNION_SDF,

    /**
     * Alpha-weighted **over-composite**: `1 - prod_i (1 - a_i · kernel(d_i))`.
     *
     * Closer to what RectSL's raster path actually does (painter's algorithm with per-rect alpha),
     * and it degrades more gracefully at a seam because both rects contribute. It is still not exact
     * there — two half-covered pixels composite to 0.75, not 1 — and the measured gap between the two
     * composites is in the docs page.
     */
    OVER
}

/**
 * A rasterization window: a pixel grid over a [WallSpec], plus the coverage kernel and the band
 * width it is allowed.
 *
 * The grid is **wall-centred**, matching [RectSLFitFigure] and [SceneProgram.sample]'s own
 * coordinates: x runs left to right over `-width/2 .. width/2` and y runs *up*, so image row 0 is
 * the top of the wall. [WallSpec.origin] is deliberately not consulted — it is a layout hint for the
 * GPU path, and honouring it here would put this raster in a different space from the sampler this
 * is differentially checked against.
 *
 * [beta] is in **wall units**, not pixels, because that is the space the SDF speaks. The conversion
 * a reader wants is [betaInPixels], and every finding in `dev_docs/RECTSL_SOFT_RASTER.md` is stated
 * in those terms, because one pixel turns out to be the interesting value.
 */
data class SoftRasterSpec(
    /** The wall the grid covers. */
    val wall: WallSpec,
    /** Pixel columns. */
    val width: Int,
    /** Pixel rows. */
    val height: Int,
    /** Coverage band width, in wall units. */
    val beta: Double,
    /** Which normalised kernel turns distance into coverage. */
    val kernel: CoverageKernel = CoverageKernel.LINEAR,
    /** How per-rect coverage becomes one number. */
    val composite: CoverageComposite = CoverageComposite.UNION_SDF
) {
    init {
        require(width > 0 && height > 0) { "a raster needs a positive size, got ${width}x$height" }
        require(beta > 0.0) { "the coverage band must be positive, got $beta" }
        require(wall.width > 0.0 && wall.height > 0.0) { "a raster needs a positive wall" }
    }

    /** Wall units across one pixel. */
    val pixelWidth: Double get() = wall.width / width

    /** Wall units down one pixel. */
    val pixelHeight: Double get() = wall.height / height

    /** How many pixels there are. */
    val pixelCount: Int get() = width * height

    /** [beta] expressed in pixel widths — the unit the bias measurement is reported in. */
    val betaInPixels: Double get() = beta / pixelWidth

    /** The wall x of pixel column [i]'s centre. */
    fun centreX(i: Int): Double = -0.5 * wall.width + (i + 0.5) * pixelWidth

    /** The wall y of pixel row [j]'s centre. y grows *up* in wall space and down in the image. */
    fun centreY(j: Int): Double = 0.5 * wall.height - (j + 0.5) * pixelHeight

    /** The wall-space box of pixel `(i, j)` as `(x0, y0, x1, y1)`, with y0 below y1. */
    fun pixelBox(i: Int, j: Int): DoubleArray {
        val x0 = -0.5 * wall.width + i * pixelWidth
        val y1 = 0.5 * wall.height - j * pixelHeight
        return doubleArrayOf(x0, y1 - pixelHeight, x0 + pixelWidth, y1)
    }
}

/**
 * The soft coverage of one pixel of [spec], tracked, from an already-transformed [frame].
 *
 * Alpha multiplies coverage under [CoverageComposite.OVER] and only *gates* it under
 * [CoverageComposite.UNION_SDF], because a union distance has nowhere to put a per-shape weight —
 * a real asymmetry between the two composites, and not an oversight.
 */
fun coverageAt(frame: SceneAdFrame, spec: SoftRasterSpec, i: Int, j: Int): AD {
    val x = spec.centreX(i)
    val y = spec.centreY(j)
    return when (spec.composite) {
        CoverageComposite.UNION_SDF -> spec.kernel.coverage(frame.distanceAt(x, y), spec.beta)
        CoverageComposite.OVER -> {
            val vis = frame.visible
            if (vis.isEmpty()) {
                frame.tape.constant(0.0)
            } else {
                var transmit = frame.tape.constant(1.0)
                for (shape in vis) {
                    val c = spec.kernel.coverage(shape.distanceAt(x, y), spec.beta) * shape.alpha
                    transmit *= (-c + 1.0)
                }
                -transmit + 1.0
            }
        }
    }
}

/**
 * The whole soft raster as coverage in `0..1`, row-major, untracked.
 *
 * Walked in row chunks with one tape each, for the reason in the file KDoc: a tape never shrinks, and
 * a raster taped in one go is measured in hundreds of megabytes.
 */
fun softRaster(
    field: SceneAdField,
    spec: SoftRasterSpec,
    time: Double,
    at: DoubleArray = field.theta,
    rowsPerChunk: Int = 8
): DoubleArray {
    require(rowsPerChunk > 0) { "rowsPerChunk must be positive" }
    val out = DoubleArray(spec.pixelCount)
    var j0 = 0
    while (j0 < spec.height) {
        val frame = field.frameAt(time, at)
        val j1 = minOf(j0 + rowsPerChunk, spec.height)
        for (j in j0 until j1) {
            for (i in 0 until spec.width) out[j * spec.width + i] = coverageAt(frame, spec, i, j).value
        }
        j0 = j1
    }
    return out
}

/** A raster loss and its exact gradient with respect to the scene's whole θ. */
class RasterLoss internal constructor(
    /** Mean squared coverage error over the image. */
    val loss: Double,
    /** `d(loss)/dθ`, one entry per [SceneAdField.theta] slot. */
    val gradient: DoubleArray
) {
    /** Root mean squared coverage error — the number worth printing. */
    val rmse: Double get() = kotlin.math.sqrt(loss)
}

/**
 * The mean squared difference between the soft raster and [target], and its exact gradient with
 * respect to every numeric leaf in the scene.
 *
 * This is the function the 2026-08-06 abandonment said a per-track lowering could not produce, and it
 * is written here on top of that same lowering plus `AD` geometry. The gradient is one backward sweep
 * per row chunk, summed — legitimate because a mean over pixels is a sum over pixels, so `d/dθ` of
 * the whole is the sum of the parts. `RectSLSoftRasterTest` checks it against central differences.
 *
 * @param target coverage in `0..1`, row-major, [SoftRasterSpec.pixelCount] long.
 */
fun softRasterLossAndGradient(
    field: SceneAdField,
    spec: SoftRasterSpec,
    time: Double,
    target: DoubleArray,
    at: DoubleArray = field.theta,
    rowsPerChunk: Int = 8
): RasterLoss {
    require(target.size == spec.pixelCount) {
        "target is ${target.size} pixels but the raster is ${spec.pixelCount}"
    }
    require(rowsPerChunk > 0) { "rowsPerChunk must be positive" }
    val grad = DoubleArray(field.theta.size)
    val pixels = spec.pixelCount.toDouble()
    var sum = 0.0
    var j0 = 0
    while (j0 < spec.height) {
        val frame = field.frameAt(time, at)
        val j1 = minOf(j0 + rowsPerChunk, spec.height)
        var chunk: AD? = null
        for (j in j0 until j1) {
            for (i in 0 until spec.width) {
                val delta = coverageAt(frame, spec, i, j) - target[j * spec.width + i]
                val term = delta * delta
                chunk = chunk?.plus(term) ?: term
            }
        }
        val total = chunk
        if (total != null) {
            sum += total.value
            val adjoint = frame.tape.backward(total)
            for (k in grad.indices) grad[k] += adjoint[frame.theta[k].index] / pixels
        }
        j0 = j1
    }
    return RasterLoss(sum / pixels, grad)
}

/**
 * Lowers every rect of this scene into a [SceneAdField], skipping — and reporting — the rects whose
 * channels have no differentiable form.
 *
 * A rect is all-or-nothing. Half a transform stack is not a shape, and a rect whose width refuses but
 * whose position lowers would silhouette at the wrong size, which is a *wrong* picture rather than a
 * partial one. A skipped rect's θ block is rolled back so that [SceneAdField.theta] and
 * [SceneAdField.shapes] keep corresponding; without that, every index after the hole is a lie.
 *
 * @param over the window every channel must be valid on. Defaults to the scene's own clock: a
 *        lowering valid only on `[0, duration]` is exactly what a fit over that scene needs, and
 *        asking for [Functions.Domain.ALL] refuses far more.
 * @throws RectLangException when not one rect lowers — a field with no shapes has a gradient of zero
 *         everywhere, which is indistinguishable from a converged fit.
 */
fun SceneProgram.toAdField(
    over: Functions.Domain = Functions.Domain(0.0, maxOf(duration, 1e-9))
): SceneAdField {
    val nodeMap = nodes.associateBy { it.id }
    val parentOf = buildMap {
        nodes.filterIsInstance<GroupNode>().forEach { group ->
            group.children.forEach { child -> put(child, group.id) }
        }
    }

    fun ancestorChain(id: String): List<GroupNode> {
        val chain = mutableListOf<GroupNode>()
        var current = parentOf[id]
        val guard = HashSet<String>()
        while (current != null && guard.add(current)) {
            (nodeMap[current] as? GroupNode)?.let { chain += it }
            current = parentOf[current]
        }
        return chain.reversed()
    }

    val theta = ArrayList<Double>()
    val slots = LinkedHashMap<String, MutableList<Int>>()
    val checkTimes = DoubleArray(CHANNEL_CHECK_FRACTIONS.size) {
        over.min + over.length * CHANNEL_CHECK_FRACTIONS[it]
    }
    var worstDisagreement = 0.0
    val skipped = LinkedHashMap<String, String>()

    // Lowers one track into a θ block, returning null and a reason when it will not. This is the
    // point at which the AD reading is pinned against the channel's own Fn.eval — a second
    // interpreter that computes a subtly different function is the one failure this whole file
    // could not otherwise detect.
    fun channel(track: Track<Double>): Pair<LoweredChannel?, String> {
        val lowering = track.toFn(over)
        val fn = lowering.valueOrNull() ?: return null to (lowering.refusalOrNull() ?: "refused")
        val disagreement = FnAutoDiff.worstValueDisagreement(fn, checkTimes)
        if (!(disagreement <= RECTSL_CHANNEL_TOLERANCE)) {
            return null to (
                "the AD reading of this channel disagrees with its own Fn.eval by $disagreement " +
                    "(> $RECTSL_CHANNEL_TOLERANCE)"
                )
        }
        if (disagreement > worstDisagreement) worstDisagreement = disagreement
        val leaves = fn.extractParameters()
        val offset = theta.size
        for (v in leaves) theta.add(v)
        for ((name, local) in freeParameterSlots(fn)) {
            slots.getOrPut(name) { mutableListOf() } += local.map { it + offset }
        }
        return LoweredChannel(fn, offset, leaves.size, FnAutoDiff.leafIndices(fn)) to ""
    }

    val shapes = mutableListOf<AdShape>()
    for (rect in nodes.filterIsInstance<RectNode>()) {
        val thetaMark = theta.size
        val slotMark = slots.mapValues { it.value.size }
        var refusal: String? = null
        val levels = mutableListOf<LoweredLevel>()
        val sources = ancestorChain(rect.id).map {
            listOf(it.x, it.y, it.rotation, it.scaleX, it.scaleY, it.alpha)
        } + listOf(listOf(rect.x, rect.y, rect.rotation, rect.scaleX, rect.scaleY, rect.alpha))
        for (level in sources) {
            val lowered = level.map { channel(it) }
            val bad = lowered.firstOrNull { it.first == null }
            if (bad != null) {
                refusal = bad.second
                break
            }
            levels += LoweredLevel(
                lowered[0].first!!, lowered[1].first!!, lowered[2].first!!,
                lowered[3].first!!, lowered[4].first!!, lowered[5].first!!
            )
        }
        var w: LoweredChannel? = null
        var h: LoweredChannel? = null
        if (refusal == null) {
            val loweredW = channel(rect.w)
            val loweredH = channel(rect.h)
            w = loweredW.first
            h = loweredH.first
            if (w == null) refusal = loweredW.second
            if (h == null && refusal == null) refusal = loweredH.second
        }
        if (refusal != null || w == null || h == null) {
            skipped[rect.id] = refusal ?: "refused"
            while (theta.size > thetaMark) theta.removeAt(theta.size - 1)
            for ((name, keep) in slotMark) {
                slots[name]?.let { list -> while (list.size > keep) list.removeAt(list.size - 1) }
            }
            slots.entries.removeAll { it.value.isEmpty() }
            continue
        }
        shapes += AdShape(rect.id, levels, w, h)
    }

    if (shapes.isEmpty()) {
        throw RectLangException(
            "no rect of this scene has a differentiable form, so its raster gradient is zero " +
                "everywhere and a fit would report convergence without having moved. Reasons: " +
                skipped.entries.take(3).joinToString("; ") { "${it.key}: ${it.value}" }
        )
    }

    return SceneAdField(
        program = this,
        shapes = shapes,
        theta = theta.toDoubleArray(),
        freeParameterSlots = slots.mapValues { it.value.toList() },
        skipped = skipped,
        worstChannelDisagreement = worstDisagreement,
        window = over
    )
}
