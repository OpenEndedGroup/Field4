@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Frequency-modulation synthesis in two pieces: [FMOperator] is one modulated sine, and [FMPatch]
 * wires several of them into the classic algorithms.
 *
 * An operator is phase modulation rather than true frequency modulation. Its phase advances at
 * `freq`, and the modulation is added to that phase where the sine is read rather than to the
 * frequency, so it does not accumulate. Operators are connected by assigning to `pmInput`, and since
 * the modulator is an ordinary [Signal] the modulation index is a gain in front of it:
 * `car.pmInput = mod * Param(3f)`. `feedbackAmount` adds an operator's own last sample to its phase,
 * and unlike the rest it is a plain `Float` fixed at construction rather than a [Param].
 *
 * [FMPatch] is three builders over that one part, each returning a bare [Signal] to be enveloped and
 * piped onward like any other source. `twoOp` is a modulator into a carrier at a frequency ratio,
 * `threeOp` is a three-operator serial chain, and `fourOp` takes an `algorithm` number choosing
 * between a serial chain, two modulators summed into one, two independent stacks mixed, and four
 * carriers added together. Any other number throws.
 */

// ═══════════════════════════════════════════════════════════════════
//  FMOperator – single FM synthesis operator
// ═══════════════════════════════════════════════════════════════════

/**
 * An FM (phase-modulation) operator: sine oscillator with an external
 * phase-modulation input and optional self-feedback.
 *
 * ### Usage patterns
 *
 * **2-operator FM:**
 * ```kotlin
 * val mod = FMOperator(freq = Param(220f))
 * val car = FMOperator(freq = Param(440f))
 * car.pmInput = mod * Param(3f)          // modulation index = 3
 * val out = car * envelope
 * ```
 *
 * **Self-feedback (metallic tones):**
 * ```kotlin
 * val op = FMOperator(freq = Param(440f), feedbackAmount = 0.7f)
 * ```
 *
 * ### FM theory refresher
 * `y(t) = sin(2π·f·t + I·modulator(t))`
 * where *I* is the modulation index.  Higher I → more sidebands.
 * This operator uses **phase modulation** (mathematically equivalent
 * to FM for sinusoidal modulators, and more stable for feedback).
 */
class FMOperator(
    /** Carrier / operator frequency. */
    val freq: Param = Param(440f),
    /** Amplitude (useful for stacking operators). */
    val amplitude: Param = Param(1f),
    /** Self-feedback amount (0 = none, ~0.7 = metallic, >1 = chaotic). */
    val feedbackAmount: Float = 0f
) : Signal() {

    /** External phase-modulation input (set to modulator * index). */
    var pmInput: Signal? = null

    private var phase: Double = 0.0
    private var prevOut: Double = 0.0   // for feedback

    private var freqBuf = FloatArray(0)
    private var ampBuf  = FloatArray(0)
    private var pmBuf   = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            ampBuf  = FloatArray(numFrames)
            pmBuf   = FloatArray(numFrames)
        }
        freq.render(freqBuf, numFrames, ctx)
        amplitude.render(ampBuf, numFrames, ctx)
        val pm = pmInput
        if (pm != null) pm.render(pmBuf, numFrames, ctx)
        else Buf.clear(pmBuf, 0, numFrames)

        val twoPi = 2.0 * PI
        val invSr = ctx.inverseSampleRate.toDouble()
        val fb = feedbackAmount.toDouble()

        for (i in 0 until numFrames) {
            val modPhase = pmBuf[i].toDouble() + fb * prevOut
            val sample = sin(twoPi * phase + modPhase)
            prevOut = sample
            output[i] = (sample * ampBuf[i]).toFloat()
            phase += freqBuf[i].toDouble() * invSr
            phase -= floor(phase)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FMPatch – pre-wired multi-operator FM algorithms
// ═══════════════════════════════════════════════════════════════════

/**
 * Convenience builder for classic FM algorithms.
 *
 * ```kotlin
 * // Classic 2-op bass
 * val bass = FMPatch.twoOp(
 *     carrierFreq = 110f,
 *     ratio = 1f,
 *     index = Param(2f),
 *     feedback = 0.3f
 * )
 *
 * // DX7-style electric piano (algorithm 5)
 * val epiano = FMPatch.threeOp(
 *     freq = 440f,
 *     ratios = floatArrayOf(1f, 1f, 14f),
 *     indices = floatArrayOf(2f, 1.5f, 0.5f)
 * )
 * ```
 */
object FMPatch {

    /**
     * 2-operator FM:  `mod → car`
     *
     * @param carrierFreq  Carrier frequency in Hz.
     * @param ratio        Modulator : carrier frequency ratio.
     * @param index        Modulation index (Signal for dynamic control).
     * @param feedback     Modulator self-feedback (0…1).
     */
    fun twoOp(
        carrierFreq: Float,
        ratio: Float = 2f,
        index: Param = Param(2f),
        feedback: Float = 0f
    ): Signal {
        val modFreq = Param(carrierFreq * ratio)
        val mod = FMOperator(freq = modFreq, feedbackAmount = feedback)
        val car = FMOperator(freq = Param(carrierFreq))
        car.pmInput = mod * index
        return car
    }

    /**
     * 3-operator serial chain:  `op3 → op2 → op1 (carrier)`
     */
    fun threeOp(
        freq: Float,
        ratios: FloatArray = floatArrayOf(1f, 2f, 3f),
        indices: FloatArray = floatArrayOf(1f, 1f, 0.5f),
        feedback: Float = 0f
    ): Signal {
        val op3 = FMOperator(freq = Param(freq * ratios[2]), feedbackAmount = feedback)
        val op2 = FMOperator(freq = Param(freq * ratios[1]))
        op2.pmInput = op3 * indices[2]
        val op1 = FMOperator(freq = Param(freq * ratios[0]))
        op1.pmInput = op2 * indices[1]
        return op1
    }

    /**
     * 4-operator FM with DX7-style algorithm selection.
     *
     * Algorithms (simplified DX7 subset):
     * - 1: `4→3→2→1`  (serial chain)
     * - 2: `(3+4)→2→1` (parallel modulators into carrier chain)
     * - 3: `4→3, 2→1, output = 1+3`  (two parallel stacks)
     * - 4: `1+2+3+4`  (all carriers, additive)
     */
    fun fourOp(
        freq: Float,
        ratios: FloatArray = floatArrayOf(1f, 1f, 2f, 3f),
        indices: FloatArray = floatArrayOf(0f, 1f, 1f, 0.5f),
        algorithm: Int = 1,
        feedback: Float = 0f
    ): Signal {
        val ops = Array(4) { i ->
            FMOperator(
                freq = Param(freq * ratios[i]),
                feedbackAmount = if (i == 3) feedback else 0f
            )
        }
        return when (algorithm) {
            1 -> {
                // 4→3→2→1
                ops[2].pmInput = ops[3] * indices[3]
                ops[1].pmInput = ops[2] * indices[2]
                ops[0].pmInput = ops[1] * indices[1]
                ops[0]
            }
            2 -> {
                // (3+4)→2→1
                ops[1].pmInput = (ops[2] + ops[3]) * indices[2]
                ops[0].pmInput = ops[1] * indices[1]
                ops[0]
            }
            3 -> {
                // two stacks: 4→3, 2→1, output = 1+3
                ops[2].pmInput = ops[3] * indices[3]
                ops[0].pmInput = ops[1] * indices[1]
                Mix(ops[0], ops[2])
            }
            4 -> {
                // all additive
                Mix(*ops)
            }
            else -> throw IllegalArgumentException("Unknown FM algorithm: $algorithm")
        }
    }
}
