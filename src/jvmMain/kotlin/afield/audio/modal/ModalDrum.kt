/**
 * [ModalDrum]: a live drum voice for Field's audio engine, one damped complex phasor per mode of a
 * [DrumModes]. Box code returns it (it is a [Signal]) and calls [ModalDrum.strike] or
 * [ModalDrum.retune] from anywhere; those only post requests, and the audio thread applies them
 * at the top of its next block, so the oscillator state stays owned by the audio thread.
 */
package afield.audio.modal

import afield.audio.synth.Signal
import afield.audio.synth.SynthContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A playable drum made from [modes]. Each mode is a phasor z ← z·r·e^(iω) whose imaginary part is
 * summed into the output; a strike adds its projection onto each mode to that mode's phasor, and a
 * retune changes ω and r while keeping the phasors, so a morphing shape glides instead of clicking.
 *
 * @param fundamental Hz of the lowest mode.
 * @param alpha base decay rate in 1/s; [beta] adds β·(fᵢ/f₀)² so higher modes die faster.
 * @param every if > 0, the drum strikes itself every [every] seconds at ([autoX], [autoY]),
 *   sample-accurately on the audio thread.
 * @param morph if not empty, a sequence of shapes (same mode count) the drum glides through, one
 *   pass every [morphPeriod] seconds and back: frequencies are interpolated between neighbours on
 *   the audio thread, so the pitch of every ringing mode bends as the outline changes.
 */
class ModalDrum @JvmOverloads constructor(
    modes: DrumModes,
    val fundamental: Double = 110.0,
    val alpha: Double = 1.5,
    val beta: Double = 0.25,
    val gain: Double = 0.15,
    val every: Double = 0.0,
    val autoX: Double = 0.0,
    val autoY: Double = 0.0,
    morph: List<DrumModes> = emptyList(),
    val morphPeriod: Double = 4.0,
) : Signal() {
    private class Bank(val modes: DrumModes, val ratio: DoubleArray, val auto: DoubleArray?)

    private val pending = AtomicReference(bankOf(modes))
    private val strikes = ConcurrentLinkedQueue<DoubleArray>()
    private var bank: Bank? = null
    private var re = DoubleArray(0)
    private var im = DoubleArray(0)
    private val morphBanks: List<Bank> = morph.map { bankOf(it) }
    private var morphFrame = 0L
    private var sinceAuto = Long.MAX_VALUE / 2

    init {
        latest = this
    }

    companion object {
        /** The most recently created drum, for inspecting a running one from another box or the backdoor. */
        @Volatile
        @JvmStatic
        var latest: ModalDrum? = null
            private set
    }

    /** Total samples this voice has rendered (written by the audio thread). */
    @Volatile
    var framesRendered: Long = 0L
        private set

    /** Peak absolute output of the most recent block (written by the audio thread). */
    @Volatile
    var lastBlockPeak: Float = 0f
        private set

    /** The modes currently sounding (the latest retune, once the audio thread has taken it). */
    val modes: DrumModes get() = pending.get().modes

    /** Strike the drum at (x, y) with a mallet of [radius]; applied at the start of the next block. */
    @JvmOverloads
    fun strike(x: Double, y: Double, radius: Double = 0.03, velocity: Double = 1.0) {
        val a = pending.get().modes.strike(x, y, radius)
        // divide by the mallet's own mass (∫g = 2πr²), so aᵢ ≈ φᵢ(x, y) whatever the radius
        for (i in a.indices) a[i] *= velocity / (2 * PI * radius * radius)
        strikes.add(a)
    }

    /** Swap in new modes (a changed shape); ringing modes keep their energy and change pitch. */
    fun retune(newModes: DrumModes) {
        pending.set(bankOf(newModes))
    }

    // built on the caller's thread, so the audio thread never projects a strike itself
    private fun bankOf(m: DrumModes) = Bank(m, m.ratios(), if (every > 0) m.strike(autoX, autoY, 0.03).map { it / (2 * PI * 0.03 * 0.03) }.toDoubleArray() else null)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val b = pending.get()
        if (b !== bank) {
            val n = b.ratio.size
            if (re.size != n) { re = re.copyOf(n); im = im.copyOf(n) }
            bank = b
        }
        while (true) {
            val a = strikes.poll() ?: break
            for (k in 0 until min(a.size, re.size)) re[k] += a[k]
        }
        val sr = ctx.sampleRate.toDouble()
        var ratio = b.ratio
        var auto = b.auto
        if (morphBanks.size > 1) {
            // triangle wave 0 → last → 0 over 2·morphPeriod, evaluated once per block
            val u = (morphFrame / (sr * morphPeriod)) % 2.0
            val pos = (if (u < 1) u else 2 - u) * (morphBanks.size - 1)
            val i0 = min(pos.toInt(), morphBanks.size - 2)
            val w = pos - i0
            val r0 = morphBanks[i0].ratio; val r1 = morphBanks[i0 + 1].ratio
            ratio = DoubleArray(min(r0.size, r1.size)) { r0[it] * (1 - w) + r1[it] * w }
            auto = if (w < 0.5) morphBanks[i0].auto else morphBanks[i0 + 1].auto
            if (re.size != ratio.size) { re = re.copyOf(ratio.size); im = im.copyOf(ratio.size) }
            morphFrame += numFrames
        }
        val n = min(re.size, ratio.size)
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        for (k in 0 until n) {
            val f = fundamental * ratio[k]
            val sigma = alpha + beta * ratio[k] * ratio[k]
            val r = if (f < sr * 0.45) exp(-sigma / sr) else 0.0
            cr[k] = r * cos(2 * PI * f / sr); ci[k] = r * sin(2 * PI * f / sr)
        }
        val autoPeriod = if (every > 0) (every * sr).toLong() else 0L
        var peak = 0f
        for (t in 0 until numFrames) {
            if (autoPeriod > 0 && sinceAuto >= autoPeriod) {
                val a = auto
                if (a != null) for (k in 0 until min(a.size, n)) re[k] += a[k]
                sinceAuto = 0
            }
            sinceAuto++
            var s = 0.0
            for (k in 0 until n) {
                val nr = re[k] * cr[k] - im[k] * ci[k]
                val ni = re[k] * ci[k] + im[k] * cr[k]
                re[k] = nr; im[k] = ni
                s += ni
            }
            val v = (s * gain).toFloat()
            output[t] = v
            if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        }
        lastBlockPeak = peak
        framesRendered += numFrames
    }
}
