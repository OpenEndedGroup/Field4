@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.stdlib

import afield.*
import afield.four.util.FieldProbeScalarBatch
import afield.four.util.FieldProbeSnapshot
import afield.four.util.ShaderFieldProbe
import org.joml.Vector4f
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private val defaultQuantileWarpProbabilities = doubleArrayOf(
    0.001, 0.01, 0.05, 0.20, 0.50, 0.80, 0.95, 0.99
)

const val FIELD_QUANTILE_WARP_INCLUDE_NAME = "quantileWarp"

fun interface QuantileFunction {
    fun quantile(p: Double): Double
}

object TargetDistributions {
    val Uniform01 = QuantileFunction { p ->
        require(p in 0.0..1.0) { "p must be in [0, 1]" }
        p
    }

    val UnitNormal = QuantileFunction { p ->
        inverseStandardNormalCdf(p)
    }

    fun uniform(min: Double, max: Double) = QuantileFunction { p ->
        require(p in 0.0..1.0) { "p must be in [0, 1]" }
        min + (max - min) * p
    }
}

data class QuantileWarpConfig(
    val probabilities: DoubleArray = defaultQuantileWarpProbabilities.copyOf(),
    val historyFrames: Int = 8,
    val smoothingAlpha: Double = 0.20,
    val epsilonRelative: Double = 1.0e-5,
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

class QuantileWarpState(
    probabilities: DoubleArray,
    inputKnots: DoubleArray,
    targetKnots: DoubleArray,
    val historySampleCount: Int
) {
    val probabilities: DoubleArray = probabilities.copyOf()
    val inputKnots: DoubleArray = inputKnots.copyOf()
    val targetKnots: DoubleArray = targetKnots.copyOf()
    val packedPushConstants: FloatArray = packQuantileWarpPushConstants(this.inputKnots, this.targetKnots)

    fun evaluate(x: Double): Double = evaluateQuantileWarpPchip(x, inputKnots, targetKnots)

    fun xLo(): Vector4f = packedVectorAt(0)
    fun xHi(): Vector4f = packedVectorAt(4)
    fun yLo(): Vector4f = packedVectorAt(8)
    fun yHi(): Vector4f = packedVectorAt(12)

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

    fun currentStateOrNull(): QuantileWarpState? = state

    fun reset(seed: QuantileWarpState? = null): AdaptiveQuantileWarp {
        history.clear()
        state = seed
        return this
    }

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

data class ShaderProbeQuantileWarpConfig(
    val minSamples: Int = 128,
    val clipLower: Double? = null,
    val clipUpper: Double? = null,
    val clipEpsilon: Double = 1.0e-6,
    val displayChannel: String? = "warp",
    val closeDisplayOnStop: Boolean = false,
    val clearDisplayOnStop: Boolean = false,
    val runloopKeySuffix: String = "shaderFieldQuantileWarp"
) {
    init {
        require(minSamples >= 1) { "minSamples must be >= 1" }
        require(clipEpsilon > 0.0) { "clipEpsilon must be > 0" }
    }
}

var Box._fieldProbeQuantileWarpController by Delegation<ShaderFieldQuantileWarpController?>()

class ShaderFieldQuantileWarpController(
    val box: Box,
    val probe: ShaderFieldProbe,
    val warp: AdaptiveQuantileWarp,
    val config: ShaderProbeQuantileWarpConfig,
    private val apply: (warp: QuantileWarpState, batch: FieldProbeScalarBatch) -> Unit
) {
    private val hookKey = "${config.runloopKeySuffix}:${box.id}"
    private var installed = false
    private var lastSnapshot: FieldProbeSnapshot? = null

    var tickCount: Int = 0
        private set

    var lastBatch: FieldProbeScalarBatch? = null
        private set

    var lastWarp: QuantileWarpState? = null
        private set

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

fun Box.fieldProbeQuantileWarp(
    probe: ShaderFieldProbe,
    warp: AdaptiveQuantileWarp = AdaptiveQuantileWarp(),
    config: ShaderProbeQuantileWarpConfig = ShaderProbeQuantileWarpConfig(),
    install: Boolean = true,
    runloop: Runloop = Runloop.runLoop,
    apply: (warp: QuantileWarpState, batch: FieldProbeScalarBatch) -> Unit
): ShaderFieldQuantileWarpController {
    _fieldProbeQuantileWarpController?.stop(runloop = runloop)

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

    val rows = warp.probabilities.indices.joinToString("") { index ->
        """
        <tr>
            <td style="padding:2px 12px 2px 0;opacity:0.72;">${format(warp.probabilities[index])}</td>
            <td style="padding:2px 12px 2px 0;">${format(warp.inputKnots[index])}</td>
            <td style="padding:2px 0 2px 0;">${format(warp.targetKnots[index])}</td>
        </tr>
        """.trimIndent()
    }

    val p10 = batch.snapshot.percentile(0.10)
    val p50 = batch.snapshot.percentile(0.50)
    val p90 = batch.snapshot.percentile(0.90)

    return """
        <div style="padding:12px;background:#07131b;color:#8fd8ff;font-family:Iosevka, monospace;font-size:11px;line-height:1.35;">
            <div style="display:flex;flex-wrap:wrap;gap:6px 14px;margin-bottom:10px;">
                <span><span style="opacity:0.65;">samples</span> ${batch.batch.samples.size}</span>
                <span><span style="opacity:0.65;">history</span> ${warp.historySampleCount}</span>
                <span><span style="opacity:0.65;">clip</span> ${format(batch.batch.clipRate)}</span>
                <span><span style="opacity:0.65;">p10/p50/p90</span> ${format(p10)} / ${format(p50)} / ${format(p90)}</span>
            </div>
            <table style="border-collapse:collapse;">
                <thead>
                    <tr>
                        <th style="text-align:left;padding:0 12px 4px 0;opacity:0.6;">p</th>
                        <th style="text-align:left;padding:0 12px 4px 0;opacity:0.6;">input x</th>
                        <th style="text-align:left;padding:0 0 4px 0;opacity:0.6;">target y</th>
                    </tr>
                </thead>
                <tbody>$rows</tbody>
            </table>
        </div>
    """.trimIndent()
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
