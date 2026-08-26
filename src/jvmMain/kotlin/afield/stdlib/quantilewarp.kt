@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.*
import afield.four.util.FieldProbeScalarBatch
import afield.four.util.FieldProbeSnapshot
import afield.four.util.ShaderFieldProbe
import afield.stdlib.linalg.Vector4f
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import afield.Table
import afield.FieldMarkdown

/**
 * Reshapes a scalar signal's distribution by fitting a monotone 8-knot PCHIP curve from the
 * measured quantiles of recent samples onto the quantiles of a chosen target distribution —
 * [TargetDistributions.UnitNormal] by default, or any [QuantileFunction].
 *
 * [AdaptiveQuantileWarp.update] takes a batch of samples, merges it with the last
 * `historyFrames` batches, and returns a [QuantileWarpState]: the knot pairs, an
 * [QuantileWarpState.evaluate] for CPU-side use, and the same 16 knots packed into four `vec4`s
 * ready for push constants via [QuantileWarpState.applyToPushConstants]. Successive states are
 * smoothed towards each other by `smoothingAlpha` and forced strictly increasing, so the curve
 * stays invertible even when the input degenerates.
 *
 * [fieldQuantileWarpIncludeSource] is the matching GLSL — `fieldQuantileWarp8` and
 * `fieldQuantileWarpMix` evaluate the identical PCHIP curve on the GPU — and is reachable from
 * shader source as the built-in include named by [FIELD_QUANTILE_WARP_INCLUDE_NAME].
 * [Box.fieldProbeQuantileWarp] closes the loop: it installs a runloop hook that drains a
 * `ShaderFieldProbe`, updates the warp, calls your `apply` callback and renders a knot table into a
 * box display channel.
 *
 * Constraints worth knowing: exactly eight strictly increasing probabilities inside `(0, 1)`, and
 * the [Box.fieldProbeQuantileWarp] entry point needs a live `Box` plus a probe configured for
 * continuous snapshot reads.
 */

private val defaultQuantileWarpProbabilities = doubleArrayOf(
    0.001, 0.01, 0.05, 0.20, 0.50, 0.80, 0.95, 0.99
)

/**
 * The name shader source uses to pull in the GLSL half of this file: `#include "quantileWarp"`.
 *
 * It resolves to a built-in include, so nothing has to be on disk. What it defines is
 * `fieldQuantileWarp8` and `fieldQuantileWarpMix`, which evaluate the *same* PCHIP curve as
 * [QuantileWarpState.evaluate] from the four `vec4`s that [QuantileWarpState.applyToPushConstants]
 * pushes — that identity is the point of the whole file.
 */
const val FIELD_QUANTILE_WARP_INCLUDE_NAME = "quantileWarp"

/**
 * The inverse CDF of the distribution you want a signal reshaped *into*: given a probability, the
 * value at that quantile. A SAM interface, so `QuantileFunction { p -> ... }` is enough.
 *
 * [AdaptiveQuantileWarp] only ever calls it at the eight probabilities in its [QuantileWarpConfig],
 * all strictly inside `(0, 1)`, and only once per warp instance — so it may be as slow as it likes.
 * The three ready-made ones are in [TargetDistributions].
 */
fun interface QuantileFunction {
    /** The value at probability [p]. Called only with `p` strictly inside `(0, 1)`. */
    fun quantile(p: Double): Double
}

/**
 * The ready-made [QuantileFunction] targets: [UnitNormal] (the default target of
 * [AdaptiveQuantileWarp]), [Uniform01], and [uniform] for an arbitrary range.
 *
 * [UnitNormal] is [inverseStandardNormalCdf] and accepts only `p` strictly inside `(0, 1)`; the two
 * uniform ones accept the closed `[0, 1]` and throw outside it.
 */
object TargetDistributions {
    /**
     * The identity: warp a signal into a flat `[0, 1]` distribution. Accepts the closed range and throws
     * outside it.
     */
    val Uniform01 = QuantileFunction { p ->
        require(p in 0.0..1.0) { "p must be in [0, 1]" }
        p
    }

    /**
     * Standard normal, mean 0 and standard deviation 1 — the default target, and the one that makes a
     * skewed signal usable with symmetric thresholds. Rejects `p` at exactly 0 or 1.
     */
    val UnitNormal = QuantileFunction { p ->
        inverseStandardNormalCdf(p)
    }

    /**
     * A flat distribution over `[min, max]` — the target to use when a shader wants a value it can index
     * or threshold in known bounds.
     */
    fun uniform(min: Double, max: Double) = QuantileFunction { p ->
        require(p in 0.0..1.0) { "p must be in [0, 1]" }
        min + (max - min) * p
    }
}

/**
 * How [AdaptiveQuantileWarp] fits: which eight quantiles to measure, how much history to pool, and
 * how fast to move.
 *
 * The default [probabilities] are `0.001, 0.01, 0.05, 0.20, 0.50, 0.80, 0.95, 0.99` — note that they
 * are **not symmetric**, spending three knots below the 5th percentile and only two
 * above the 95th. There must be exactly eight, strictly increasing, strictly inside `(0, 1)`, or
 * construction throws; that is what makes the state pack into four `vec4`s.
 *
 * [historyFrames] (default **8**) is how many `update` batches are pooled before quantiles are taken,
 * so the knots are fitted on eight frames' worth of samples rather than one. [smoothingAlpha]
 * (default **0.20**) lerps the previous knots that fraction of the way to the new ones on every
 * update, so a step change in the signal is followed geometrically — the remaining gap after k
 * updates is `(1 − alpha)^k`, measured and pinned in `QuantileWarpTest`. It takes about a dozen
 * updates to close 90% of a jump, which is a feature on a noisy probe and a nuisance on a slow one.
 *
 * [epsilonRelative] and [epsilonAbsolute] set the minimum gap forced between adjacent knots after
 * smoothing, which is what keeps the curve invertible when the input degenerates to a constant.
 */
data class QuantileWarpConfig(
    /**
     * The eight quantiles to measure. Exactly eight, strictly increasing, strictly inside `(0, 1)`.
     *
     * The default spends three knots below the 5th percentile and two above the 95th, on the assumption
     * that the interesting end of a probe signal is the quiet one.
     */
    val probabilities: DoubleArray = defaultQuantileWarpProbabilities.copyOf(),
    /**
     * How many `update` batches are pooled before the quantiles are taken. At least 1; 1 means fit on the
     * current batch alone.
     */
    val historyFrames: Int = 8,
    /**
     * How far each update moves the knots towards the newly measured ones, in `[0, 1]`.
     *
     * The gap remaining after `k` updates is `(1 − alpha)^k`, so the default takes about a dozen updates
     * to close 90% of a step change. 1.0 disables smoothing entirely.
     */
    val smoothingAlpha: Double = 0.20,
    /** Minimum gap between adjacent knots as a fraction of the knot range; keeps the curve invertible. */
    val epsilonRelative: Double = 1.0e-5,
    /**
     * Minimum absolute gap between adjacent knots — the floor that survives a signal collapsing to a
     * constant, where the relative term is zero. Must be strictly positive.
     */
    val epsilonAbsolute: Double = 1.0e-6
) {
    init {
        require(probabilities.size == 8) { "Quantile warp expects exactly 8 knot probabilities" }
        require((0 until probabilities.size - 1).all { index -> probabilities[index] < probabilities[index + 1] }) {
            "Probabilities must be strictly increasing"
        }
        require(probabilities.first() > 0.0 && probabilities.last() < 1.0) {
            "Probabilities must lie strictly inside (0, 1)"
        }
        require(historyFrames >= 1) { "historyFrames must be >= 1" }
        require(smoothingAlpha in 0.0..1.0) { "smoothingAlpha must be in [0, 1]" }
        require(epsilonRelative >= 0.0) { "epsilonRelative must be >= 0" }
        require(epsilonAbsolute > 0.0) { "epsilonAbsolute must be > 0" }
    }
}

/**
 * One fitted warp: eight [inputKnots] measured from the signal, eight [targetKnots] from the target
 * distribution, and the monotone PCHIP curve through them.
 *
 * [evaluate] is the curve on the CPU; [packedPushConstants] is the same sixteen numbers (eight input
 * then eight target) as `FloatArray`, reachable as four `Vector4f`s via [xLo]/[xHi]/[yLo]/[yHi] or
 * pushed by name with [applyToPushConstants], and the GLSL include named by
 * [FIELD_QUANTILE_WARP_INCLUDE_NAME] evaluates the identical curve on the GPU.
 *
 * **Two things to know about [evaluate].** Eight knots is an approximation: against the exact answer
 * it is fitting — `Φ⁻¹(F̂(v))`, the inverse target CDF of the empirical CDF — it lands within about
 * **0.05** over the 2nd-to-98th percentile of a log-normal, and drifts further in the extreme tails.
 * And **outside** `inputKnots.first()..inputKnots.last()` it extrapolates *linearly* from the endpoint
 * tangent, with nothing clamping the result: on a log-normal fit whose lowest knot is 0.038,
 * `evaluate(0.0)` returns about −3.7 and `evaluate(-1.0)` about −21. Clamp your input if the signal
 * can leave the range you fitted on. Both bounds are pinned in `QuantileWarpTest`.
 *
 * The arrays are copied in on construction, so a state is a snapshot and not a view.
 * [historySampleCount] is how many pooled samples the quantiles came from. [Companion.identity01] is
 * the do-nothing warp: input knots equal to the probabilities, so it is the identity between them.
 */
class QuantileWarpState(
    probabilities: DoubleArray,
    inputKnots: DoubleArray,
    targetKnots: DoubleArray,
    /** How many pooled samples these quantiles were measured from. */
    val historySampleCount: Int
) {
    /** The eight probabilities the knots were measured at. A copy. */
    val probabilities: DoubleArray = probabilities.copyOf()
    /** The measured value of the signal at each probability — the x axis of the curve. Strictly increasing. */
    val inputKnots: DoubleArray = inputKnots.copyOf()
    /** The target distribution's value at each probability — the y axis of the curve. */
    val targetKnots: DoubleArray = targetKnots.copyOf()
    /** The sixteen numbers a shader needs: eight [inputKnots] then eight [targetKnots], as `Float`. */
    val packedPushConstants: FloatArray = packQuantileWarpPushConstants(this.inputKnots, this.targetKnots)

    /**
     * The warp itself: a signal value in, its position in the target distribution out.
     *
     * Two limits worth knowing. Eight knots is an approximation of `Φ⁻¹(F̂(v))` — within about 0.05 over
     * the 2nd-to-98th percentile of a log-normal, worse in the extreme tails. And outside
     * `inputKnots.first()..inputKnots.last()` it extrapolates *linearly* with nothing clamping the result
     * (on one fitted log-normal, `evaluate(-1.0)` returns about −21), so clamp the input if the signal can
     * leave the range it was fitted on.
     */
    fun evaluate(x: Double): Double = evaluateQuantileWarpPchip(x, inputKnots, targetKnots)

    /** Input knots 0..3, ready for a `vec4` push constant. */
    fun xLo(): Vector4f = packedVectorAt(0)
    /** Input knots 4..7. */
    fun xHi(): Vector4f = packedVectorAt(4)
    /** Target knots 0..3. */
    fun yLo(): Vector4f = packedVectorAt(8)
    /** Target knots 4..7. */
    fun yHi(): Vector4f = packedVectorAt(12)

    /**
     * Push all four `vec4`s through one `setVec4` callback, under the names the GLSL include expects.
     *
     * The default names match `fieldQuantileWarp8`'s parameters, so a shader that includes
     * [FIELD_QUANTILE_WARP_INCLUDE_NAME] and a box that calls this need agree on nothing else.
     */
    fun applyToPushConstants(
        setVec4: (name: String, value: Vector4f) -> Unit,
        xLoName: String = "warpXLo",
        xHiName: String = "warpXHi",
        yLoName: String = "warpYLo",
        yHiName: String = "warpYHi"
    ) {
        setVec4(xLoName, xLo())
        setVec4(xHiName, xHi())
        setVec4(yLoName, yLo())
        setVec4(yHiName, yHi())
    }

    private fun packedVectorAt(offset: Int): Vector4f = Vector4f(
        packedPushConstants[offset],
        packedPushConstants[offset + 1],
        packedPushConstants[offset + 2],
        packedPushConstants[offset + 3]
    )

    /** The sample count and both knot arrays to four decimal places — a warp you can read in a log line. */
    override fun toString(): String = buildString {
        append("QuantileWarpState(")
        append("historySampleCount=")
        append(historySampleCount)
        append(", inputKnots=")
        append(inputKnots.joinToString(prefix = "[", postfix = "]") { "%.4f".format(Locale.US, it) })
        append(", targetKnots=")
        append(targetKnots.joinToString(prefix = "[", postfix = "]") { "%.4f".format(Locale.US, it) })
        append(")")
    }

    companion object {
        /**
         * The do-nothing warp: input knots equal to the target knots, so it is the identity between them.
         *
         * Useful as a seed for [AdaptiveQuantileWarp.reset], and as the state to push before any samples have
         * arrived — a shader then behaves as if unwarped rather than reading uninitialised constants.
         */
        fun identity01(probabilities: DoubleArray = defaultQuantileWarpProbabilities): QuantileWarpState {
            require(probabilities.size == 8) { "identity01 expects 8 probabilities" }
            return QuantileWarpState(
                probabilities = probabilities,
                inputKnots = probabilities,
                targetKnots = probabilities,
                historySampleCount = 0
            )
        }
    }
}

/**
 * Reshapes a signal's distribution into a target one, adaptively: hand [update] a batch of samples
 * and get back a [QuantileWarpState] whose [QuantileWarpState.evaluate] maps that signal onto the
 * target — [TargetDistributions.UnitNormal] unless you pass another.
 *
 * It works, and it works out of sample: fitted on one log-normal draw of 4000 and applied to a
 * *disjoint* second draw, skewness goes from 11.7 to 0.007 and excess kurtosis from 255 to −0.17,
 * with the mean and standard deviation landing within 0.01 of 0 and 1. Pinned in `QuantileWarpTest`.
 *
 * The target knots are computed once, in the constructor. Each [update] appends its batch to a
 * history of the last `historyFrames` batches (default 8), pools and sorts the lot, takes the eight
 * empirical quantiles, forces them strictly increasing, lerps them `smoothingAlpha` (default 0.20) of
 * the way from the previous state's knots, and forces them strictly increasing again. So the *first*
 * update is unsmoothed and every one after it lags — see [QuantileWarpConfig]. `update` throws on an
 * empty batch. [reset] clears both the history and the state, optionally seeding a starting state;
 * [currentStateOrNull] is the last state without recomputing.
 */
class AdaptiveQuantileWarp(
    private val config: QuantileWarpConfig = QuantileWarpConfig(),
    target: QuantileFunction = TargetDistributions.UnitNormal
) {
    private val probabilities = config.probabilities.copyOf()
    private val targetKnots = DoubleArray(probabilities.size) { index ->
        target.quantile(probabilities[index])
    }
    private val history = ArrayDeque<DoubleArray>()
    private var state: QuantileWarpState? = null

    /** The last state [update] produced, or null before the first one. Does not recompute anything. */
    fun currentStateOrNull(): QuantileWarpState? = state

    /**
     * Forget the pooled history and the current state, optionally starting again from [seed].
     *
     * Call this when the signal's meaning changes — a different pass, a different scene — rather than
     * waiting for the smoothing to walk the knots across.
     */
    fun reset(seed: QuantileWarpState? = null): AdaptiveQuantileWarp {
        history.clear()
        state = seed
        return this
    }

    /**
     * Fold a batch of samples in and return the new warp.
     *
     * Appends the batch to the history, pools and sorts the last `historyFrames` batches, takes the eight
     * empirical quantiles, forces them strictly increasing, lerps `smoothingAlpha` of the way from the
     * previous knots, and forces them increasing again. So the *first* update is unsmoothed and every
     * later one lags. Throws on an empty batch.
     */
    fun update(samples: DoubleArray): QuantileWarpState {
        require(samples.isNotEmpty()) { "samples must not be empty" }

        history.addLast(samples.copyOf())
        while (history.size > config.historyFrames) {
            history.removeFirst()
        }

        val merged = mergeHistory(history)
        merged.sort()

        val rawInputKnots = DoubleArray(probabilities.size) { index ->
            empiricalQuantile(merged, probabilities[index])
        }
        val strictInputKnots = makeStrictlyIncreasing(
            values = rawInputKnots,
            epsilonRelative = config.epsilonRelative,
            epsilonAbsolute = config.epsilonAbsolute
        )

        val smoothedInputKnots = state?.let { previous ->
            DoubleArray(strictInputKnots.size) { index ->
                lerp(previous.inputKnots[index], strictInputKnots[index], config.smoothingAlpha)
            }
        } ?: strictInputKnots

        return QuantileWarpState(
            probabilities = probabilities,
            inputKnots = makeStrictlyIncreasing(
                values = smoothedInputKnots,
                epsilonRelative = config.epsilonRelative,
                epsilonAbsolute = config.epsilonAbsolute
            ),
            targetKnots = targetKnots,
            historySampleCount = merged.size
        ).also {
            state = it
        }
    }

    private fun mergeHistory(history: ArrayDeque<DoubleArray>): DoubleArray {
        val total = history.sumOf { it.size }
        val merged = DoubleArray(total)
        var offset = 0
        history.forEach { frame ->
            frame.copyInto(merged, destinationOffset = offset)
            offset += frame.size
        }
        return merged
    }
}

/**
 * How [Box.fieldProbeQuantileWarp] drives a warp off a GPU probe: when a batch is big enough to be
 * worth fitting, what to clip, and where to draw the knots.
 */
data class ShaderProbeQuantileWarpConfig(
    /**
     * Fewest probe samples worth fitting on. A tick that cannot gather this many does nothing at all
     * rather than fitting a worse curve.
     */
    val minSamples: Int = 128,
    /**
     * Discard samples at or below this before fitting; null keeps everything. The usual reason is a
     * background value that would otherwise own the lower knots.
     */
    val clipLower: Double? = null,
    /** Discard samples at or above this before fitting; null keeps everything. */
    val clipUpper: Double? = null,
    /**
     * Tolerance used when comparing against the clip bounds, so a sample exactly on the bound is decided
     * consistently. Must be strictly positive.
     */
    val clipEpsilon: Double = 1.0e-6,
    /** Box display channel for the live knot table, or null to draw nothing. */
    val displayChannel: String? = "warp",
    /** Close the display channel when the controller stops, rather than leaving the last table up. */
    val closeDisplayOnStop: Boolean = false,
    /** Blank the display channel when the controller stops. */
    val clearDisplayOnStop: Boolean = false,
    /**
     * Suffix of the runloop hook key, which is `"<suffix>:<box id>"`. Change it only to run two warps on
     * one box.
     */
    val runloopKeySuffix: String = "shaderFieldQuantileWarp"
) {
    init {
        require(minSamples >= 1) { "minSamples must be >= 1" }
        require(clipEpsilon > 0.0) { "clipEpsilon must be > 0" }
    }
}

/**
 * The controller [Box.fieldProbeQuantileWarp] installed on this box, if any.
 *
 * Held on the box so that re-running the box stops the previous controller instead of stacking a
 * second runloop hook on top of it — the underscore says it is plumbing, not an API.
 */
var Box._fieldProbeQuantileWarpController by Delegation<ShaderFieldQuantileWarpController?>()

/**
 * The running loop: drain a `ShaderFieldProbe`, refit the warp, hand it to your `apply` callback, draw
 * the knots.
 *
 * Made by [Box.fieldProbeQuantileWarp] rather than directly. It is idempotent about installation and
 * de-duplicates by probe snapshot, so a frame that produced no new probe data costs nothing and the
 * warp is never fitted twice on the same samples.
 */
class ShaderFieldQuantileWarpController(
    /** The box that owns the runloop hook and the display channel. */
    val box: Box,
    /** Where the samples come from. Must be configured for continuous snapshot reads. */
    val probe: ShaderFieldProbe,
    /** The fitter being driven. Its config decides the pooling and smoothing. */
    val warp: AdaptiveQuantileWarp,
    /** How this controller reads the probe and what it draws. */
    val config: ShaderProbeQuantileWarpConfig,
    private val apply: (warp: QuantileWarpState, batch: FieldProbeScalarBatch) -> Unit
) {
    private val hookKey = "${config.runloopKeySuffix}:${box.id}"
    private var installed = false
    private var lastSnapshot: FieldProbeSnapshot? = null

    /** How many times a *new* probe snapshot has been fitted — not how many frames have passed. */
    var tickCount: Int = 0
        private set

    /** The most recent batch that was fitted, or null if none has been yet. */
    var lastBatch: FieldProbeScalarBatch? = null
        private set

    /** The warp fitted from [lastBatch], or null if none has been yet. */
    var lastWarp: QuantileWarpState? = null
        private set

    /**
     * One attempt: read the probe, and if there is a new snapshot with enough samples, refit and apply.
     *
     * Returns null when there was nothing new to do, which is the common case at frame rate. Called for
     * you by the runloop hook [install] adds; call it directly only in a test.
     */
    fun tick(): QuantileWarpState? {
        val batch = probe.readLatestScalarBatch(
            minSamples = config.minSamples,
            clipLower = config.clipLower,
            clipUpper = config.clipUpper,
            clipEpsilon = config.clipEpsilon
        ) ?: return null

        if (batch.snapshot === lastSnapshot) return null

        val nextWarp = warp.update(batch.batch.samples)
        apply(nextWarp, batch)

        lastSnapshot = batch.snapshot
        lastBatch = batch
        lastWarp = nextWarp
        tickCount += 1

        config.displayChannel?.let { channel ->
            box.display(channel, shaderFieldQuantileWarpSummaryHtml(batch, nextWarp))
        }

        return nextWarp
    }

    /** Add the pre-run hook that calls [tick] every frame. Idempotent. */
    fun install(runloop: Runloop = Runloop.runLoop): ShaderFieldQuantileWarpController {
        if (installed) return this
        runloop.preRun[hookKey] = sequence {
            while (true) {
                tick()
                yield(Unit)
            }
        }
        installed = true
        return this
    }

    /**
     * Remove the hook, optionally tidy the display, and forget this controller on the box.
     *
     * Safe to call twice, and safe to call on a controller that was never installed.
     */
    fun stop(
        runloop: Runloop = Runloop.runLoop,
        closeDisplay: Boolean = config.closeDisplayOnStop,
        clearDisplay: Boolean = config.clearDisplayOnStop
    ): ShaderFieldQuantileWarpController {
        runloop.preRun.remove(hookKey)
        installed = false

        config.displayChannel?.let { channel ->
            if (clearDisplay) box.displayClear(channel)
            if (closeDisplay) box.displayClose(channel)
        }

        if (box._fieldProbeQuantileWarpController === this) {
            box.properties.remove("_fieldProbeQuantileWarpController")
        }
        return this
    }
}

/**
 * Close the loop: let a shader's own output decide how that shader's values are scaled, every frame.
 *
 * Reads a `ShaderFieldProbe`'s samples off the GPU, refits an [AdaptiveQuantileWarp] on them, and
 * calls [apply] with the new [QuantileWarpState] — which is where you push
 * [QuantileWarpState.applyToPushConstants] into the pipeline that produced the data. The result is a
 * shader whose thresholds mean the same thing whatever the scene does to its dynamic range, which is
 * not something a fixed constant can do.
 *
 * Stops any controller already installed on this box before installing a new one, and requires a probe
 * configured for continuous snapshot reads (it throws otherwise, rather than silently never firing).
 * Pass `install = false` to build the controller and drive [ShaderFieldQuantileWarpController.tick]
 * yourself.
 */
fun Box.fieldProbeQuantileWarp(
    probe: ShaderFieldProbe,
    warp: AdaptiveQuantileWarp = AdaptiveQuantileWarp(),
    config: ShaderProbeQuantileWarpConfig = ShaderProbeQuantileWarpConfig(),
    install: Boolean = true,
    runloop: Runloop = Runloop.runLoop,
    apply: (warp: QuantileWarpState, batch: FieldProbeScalarBatch) -> Unit
): ShaderFieldQuantileWarpController {
    _fieldProbeQuantileWarpController?.stop(runloop = runloop)
    probe.requireContinuousSnapshotReads()

    return ShaderFieldQuantileWarpController(
        box = this,
        probe = probe,
        warp = warp,
        config = config,
        apply = apply
    ).also { runtime ->
        _fieldProbeQuantileWarpController = runtime
        if (install) runtime.install(runloop)
    }
}

internal fun fieldQuantileWarpIncludeSource(): String = """
    const int FIELD_QUANTILE_WARP_KNOTS = 8;

    float fieldQuantileWarpGetPacked8(vec4 lo, vec4 hi, int i) {
        return (i < 4) ? lo[i] : hi[i - 4];
    }

    void fieldQuantileWarpUnpack8(
        vec4 xLo,
        vec4 xHi,
        vec4 yLo,
        vec4 yHi,
        out float xs[FIELD_QUANTILE_WARP_KNOTS],
        out float ys[FIELD_QUANTILE_WARP_KNOTS]
    ) {
        for (int i = 0; i < FIELD_QUANTILE_WARP_KNOTS; ++i) {
            xs[i] = fieldQuantileWarpGetPacked8(xLo, xHi, i);
            ys[i] = fieldQuantileWarpGetPacked8(yLo, yHi, i);
        }
    }

    float fieldQuantileWarpInteriorSlope(float hPrev, float hNext, float dPrev, float dNext) {
        if (dPrev == 0.0 || dNext == 0.0 || dPrev * dNext <= 0.0) {
            return 0.0;
        }

        float w1 = 2.0 * hNext + hPrev;
        float w2 = hNext + 2.0 * hPrev;
        return (w1 + w2) / (w1 / dPrev + w2 / dNext);
    }

    float fieldQuantileWarpEndpointSlopeLeft(float h0, float h1, float d0, float d1) {
        float m = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1);

        if (m * d0 <= 0.0) {
            return 0.0;
        }

        if (d0 * d1 < 0.0 && abs(m) > abs(3.0 * d0)) {
            return 3.0 * d0;
        }

        return m;
    }

    float fieldQuantileWarpEndpointSlopeRight(float hLast, float hPrev, float dLast, float dPrev) {
        float m = ((2.0 * hLast + hPrev) * dLast - hLast * dPrev) / (hLast + hPrev);

        if (m * dLast <= 0.0) {
            return 0.0;
        }

        if (dLast * dPrev < 0.0 && abs(m) > abs(3.0 * dLast)) {
            return 3.0 * dLast;
        }

        return m;
    }

    void fieldQuantileWarpTangents(
        float xs[FIELD_QUANTILE_WARP_KNOTS],
        float ys[FIELD_QUANTILE_WARP_KNOTS],
        out float ms[FIELD_QUANTILE_WARP_KNOTS]
    ) {
        float h[FIELD_QUANTILE_WARP_KNOTS - 1];
        float d[FIELD_QUANTILE_WARP_KNOTS - 1];

        for (int i = 0; i < FIELD_QUANTILE_WARP_KNOTS - 1; ++i) {
            h[i] = max(xs[i + 1] - xs[i], 1e-20);
            d[i] = (ys[i + 1] - ys[i]) / h[i];
        }

        ms[0] = fieldQuantileWarpEndpointSlopeLeft(h[0], h[1], d[0], d[1]);

        for (int i = 1; i < FIELD_QUANTILE_WARP_KNOTS - 1; ++i) {
            ms[i] = fieldQuantileWarpInteriorSlope(h[i - 1], h[i], d[i - 1], d[i]);
        }

        ms[FIELD_QUANTILE_WARP_KNOTS - 1] = fieldQuantileWarpEndpointSlopeRight(
            h[FIELD_QUANTILE_WARP_KNOTS - 2],
            h[FIELD_QUANTILE_WARP_KNOTS - 3],
            d[FIELD_QUANTILE_WARP_KNOTS - 2],
            d[FIELD_QUANTILE_WARP_KNOTS - 3]
        );
    }

    float fieldQuantileWarpHermite(
        float x,
        float x0,
        float x1,
        float y0,
        float y1,
        float m0,
        float m1
    ) {
        float h = max(x1 - x0, 1e-20);
        float t = clamp((x - x0) / h, 0.0, 1.0);
        float t2 = t * t;
        float t3 = t2 * t;

        float h00 =  2.0 * t3 - 3.0 * t2 + 1.0;
        float h10 =        t3 - 2.0 * t2 + t;
        float h01 = -2.0 * t3 + 3.0 * t2;
        float h11 =        t3 -       t2;

        return h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1;
    }

    float fieldQuantileWarp8(float x, vec4 xLo, vec4 xHi, vec4 yLo, vec4 yHi) {
        float xs[FIELD_QUANTILE_WARP_KNOTS];
        float ys[FIELD_QUANTILE_WARP_KNOTS];
        float ms[FIELD_QUANTILE_WARP_KNOTS];

        fieldQuantileWarpUnpack8(xLo, xHi, yLo, yHi, xs, ys);
        fieldQuantileWarpTangents(xs, ys, ms);

        if (x <= xs[0]) {
            return ys[0] + ms[0] * (x - xs[0]);
        }

        if (x >= xs[FIELD_QUANTILE_WARP_KNOTS - 1]) {
            return ys[FIELD_QUANTILE_WARP_KNOTS - 1] + ms[FIELD_QUANTILE_WARP_KNOTS - 1] * (x - xs[FIELD_QUANTILE_WARP_KNOTS - 1]);
        }

        int seg = 0;
        for (int i = 0; i < FIELD_QUANTILE_WARP_KNOTS - 1; ++i) {
            if (x <= xs[i + 1]) {
                seg = i;
                break;
            }
        }

        return fieldQuantileWarpHermite(
            x,
            xs[seg], xs[seg + 1],
            ys[seg], ys[seg + 1],
            ms[seg], ms[seg + 1]
        );
    }

    float fieldQuantileWarpMix(
        float x,
        float strength,
        vec4 xLo,
        vec4 xHi,
        vec4 yLo,
        vec4 yHi
    ) {
        float shaped = fieldQuantileWarp8(x, xLo, xHi, yLo, yHi);
        return mix(x, shaped, clamp(strength, 0.0, 1.0));
    }
""".trimIndent()

private fun shaderFieldQuantileWarpSummaryHtml(
    batch: FieldProbeScalarBatch,
    warp: QuantileWarpState
): String {
    fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

    val knots = Table(listOf("p", "input x", "target y"))
        .rows(warp.probabilities.indices.map { i ->
            listOf(warp.probabilities[i], warp.inputKnots[i], warp.targetKnots[i])
        })
        .format("p") { format(it as Double) }
        .format("input x") { format(it as Double) }
        .format("target y") { format(it as Double) }

    val p10 = batch.snapshot.percentile(0.10)
    val p50 = batch.snapshot.percentile(0.50)
    val p90 = batch.snapshot.percentile(0.90)

    // The chip strip was a flex row of <span><span style="opacity:0.65">label</span> value</span>
    // pairs; as markdown it is one line, and the labels take the muted token.
    return FieldMarkdown.card(
        """
        {muted:samples} ${batch.batch.samples.size} · {muted:history} ${warp.historySampleCount} ·
        {muted:clip} ${format(batch.batch.clipRate)} ·
        {muted:p10/p50/p90} ${format(p10)} / ${format(p50)} / ${format(p90)}

        ${knots.toHTML()}
        """
    )
}

private fun packQuantileWarpPushConstants(inputKnots: DoubleArray, targetKnots: DoubleArray): FloatArray {
    require(inputKnots.size == 8) { "Expected 8 input knots" }
    require(targetKnots.size == 8) { "Expected 8 target knots" }

    val packed = FloatArray(16)
    for (index in 0 until 8) {
        packed[index] = inputKnots[index].toFloat()
        packed[index + 8] = targetKnots[index].toFloat()
    }
    return packed
}

private fun empiricalQuantile(sorted: DoubleArray, p: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val index = p * (sorted.size - 1)
    val lo = floor(index).toInt()
    val hi = ceil(index).toInt()
    if (lo == hi) return sorted[lo]
    val t = index - lo
    return lerp(sorted[lo], sorted[hi], t)
}

private fun makeStrictlyIncreasing(
    values: DoubleArray,
    epsilonRelative: Double,
    epsilonAbsolute: Double
): DoubleArray {
    val out = values.copyOf()
    val span = abs(out.last() - out.first())
    val epsilon = max(epsilonAbsolute, epsilonRelative * max(1.0, span))

    for (index in 1 until out.size) {
        if (out[index] <= out[index - 1]) {
            out[index] = out[index - 1] + epsilon
        }
    }

    return out
}

private fun evaluateQuantileWarpPchip(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
    require(xs.size == 8) { "Expected 8 x knots" }
    require(ys.size == 8) { "Expected 8 y knots" }

    val tangents = computePchipTangents(xs, ys)

    if (x <= xs.first()) {
        return ys.first() + tangents.first() * (x - xs.first())
    }
    if (x >= xs.last()) {
        return ys.last() + tangents.last() * (x - xs.last())
    }

    var segment = 0
    while (segment < xs.lastIndex - 1 && x > xs[segment + 1]) {
        segment += 1
    }

    return hermiteSegment(
        x = x,
        x0 = xs[segment],
        x1 = xs[segment + 1],
        y0 = ys[segment],
        y1 = ys[segment + 1],
        m0 = tangents[segment],
        m1 = tangents[segment + 1]
    )
}

private fun computePchipTangents(xs: DoubleArray, ys: DoubleArray): DoubleArray {
    val h = DoubleArray(xs.size - 1) { index ->
        max(xs[index + 1] - xs[index], 1.0e-20)
    }
    val d = DoubleArray(xs.size - 1) { index ->
        (ys[index + 1] - ys[index]) / h[index]
    }
    val m = DoubleArray(xs.size)

    m[0] = pchipEndpointSlopeLeft(h[0], h[1], d[0], d[1])
    for (index in 1 until xs.lastIndex) {
        m[index] = pchipInteriorSlope(h[index - 1], h[index], d[index - 1], d[index])
    }
    m[xs.lastIndex] = pchipEndpointSlopeRight(
        h[h.lastIndex],
        h[h.lastIndex - 1],
        d[d.lastIndex],
        d[d.lastIndex - 1]
    )
    return m
}

private fun pchipInteriorSlope(hPrev: Double, hNext: Double, dPrev: Double, dNext: Double): Double {
    if (dPrev == 0.0 || dNext == 0.0 || dPrev * dNext <= 0.0) return 0.0
    val w1 = 2.0 * hNext + hPrev
    val w2 = hNext + 2.0 * hPrev
    return (w1 + w2) / (w1 / dPrev + w2 / dNext)
}

private fun pchipEndpointSlopeLeft(h0: Double, h1: Double, d0: Double, d1: Double): Double {
    val m = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
    if (m * d0 <= 0.0) return 0.0
    if (d0 * d1 < 0.0 && abs(m) > abs(3.0 * d0)) return 3.0 * d0
    return m
}

private fun pchipEndpointSlopeRight(hLast: Double, hPrev: Double, dLast: Double, dPrev: Double): Double {
    val m = ((2.0 * hLast + hPrev) * dLast - hLast * dPrev) / (hLast + hPrev)
    if (m * dLast <= 0.0) return 0.0
    if (dLast * dPrev < 0.0 && abs(m) > abs(3.0 * dLast)) return 3.0 * dLast
    return m
}

private fun hermiteSegment(
    x: Double,
    x0: Double,
    x1: Double,
    y0: Double,
    y1: Double,
    m0: Double,
    m1: Double
): Double {
    val h = max(x1 - x0, 1.0e-20)
    val t = ((x - x0) / h).coerceIn(0.0, 1.0)
    val t2 = t * t
    val t3 = t2 * t

    val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
    val h10 = t3 - 2.0 * t2 + t
    val h01 = -2.0 * t3 + 3.0 * t2
    val h11 = t3 - t2

    return h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
}

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/**
 * The standard normal quantile function `Φ⁻¹(p)` — Acklam's rational approximation, in three branches
 * that meet at `p = 0.02425` and its reflection.
 *
 * Accurate to about 1e-9 absolute, which is more than the eight-knot fit above it can use: it returns
 * 1.959964 at p=0.975 and −3.090232 at p=0.001, matching the published quantiles to six decimals, and
 * is antisymmetric across the branch boundary to the same tolerance. Checked in `QuantileWarpTest`.
 *
 * `p` must be **strictly** inside `(0, 1)` or it throws — there is no ±infinity at the ends. This is
 * what [TargetDistributions.UnitNormal] is, so the eight warp probabilities being strictly inside
 * `(0, 1)` is not a stylistic constraint but this function's precondition.
 */
fun inverseStandardNormalCdf(p: Double): Double {
    require(p > 0.0 && p < 1.0) { "p must lie strictly inside (0, 1)" }

    val a = doubleArrayOf(
        -3.969683028665376e+01,
        2.209460984245205e+02,
        -2.759285104469687e+02,
        1.383577518672690e+02,
        -3.066479806614716e+01,
        2.506628277459239e+00
    )
    val b = doubleArrayOf(
        -5.447609879822406e+01,
        1.615858368580409e+02,
        -1.556989798598866e+02,
        6.680131188771972e+01,
        -1.328068155288572e+01
    )
    val c = doubleArrayOf(
        -7.784894002430293e-03,
        -3.223964580411365e-01,
        -2.400758277161838e+00,
        -2.549732539343734e+00,
        4.374664141464968e+00,
        2.938163982698783e+00
    )
    val d = doubleArrayOf(
        7.784695709041462e-03,
        3.224671290700398e-01,
        2.445134137142996e+00,
        3.754408661907416e+00
    )

    val pLow = 0.02425
    val pHigh = 1.0 - pLow

    return when {
        p < pLow -> {
            val q = sqrt(-2.0 * ln(p))
            (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }

        p <= pHigh -> {
            val q = p - 0.5
            val r = q * q
            (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
        }

        else -> {
            val q = sqrt(-2.0 * ln(1.0 - p))
            -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }
    }
}
