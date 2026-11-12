@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Something placed on the strings of a [Piano]'s keys, the way John Cage prepared a piano: a bolt, a
 * strip of rubber, a loose nut. Every control is a [Param], so a bolt can slide along the string
 * while the note rings.
 *
 * It acts on each mode of the string by how far that mode moves where it sits, `sin(nπx)` for
 * partial `n` at [position] `x`, the same weighting the close microphone uses:
 *
 * - [mass] (grams) pulls the modes that move under it down in pitch. Each partial moves by its own
 *   amount, so the harmonic series breaks into a gong-like chord. A bolt of a few grams on a treble
 *   string, whose string weighs about 2 g, moves partials by several semitones; the same bolt on a
 *   bass string (100 g or more) barely detunes it. A negative mass pulls them up instead.
 * - [damping] (per second, at a mode's loudest point) drains the modes that move under it, the way a
 *   rubber wedge does: 20 shortens a mode at an antinode to about a third of a second, and leaves a
 *   mode with a node at [position] ringing. A negative value cancels the string's own losses, down
 *   to a mode that does not decay at all.
 * - [rattle] is a loose object with [gap] millimetres of play: each time the string catches it, it
 *   rings at [tone], as hard as the string was moving. Loud notes buzz; as the note decays below the
 *   gap, the buzz stops.
 *
 * [position], [mass] and [damping] are read every 32 samples; the rattle runs every sample.
 * [string] picks one of a key's strings (0, 1 or 2), or -1 for all of them, as a screw wedged
 * between two strings of a three-string note would not touch the third. The hammer's contact, one
 * to four milliseconds long, is computed on the unprepared string.
 *
 * ```kotlin
 * val piano = Piano(preparations = listOf(
 *     PianoPreparation.bolt(60..72, at = 0.3, grams = 6.0),
 *     PianoPreparation.rubber(36..48, at = 0.12, amount = 25.0),
 *     PianoPreparation.rattle(73..84, at = 0.2, level = 1.0),
 * ))
 * ```
 */
class PianoPreparation(
    /** MIDI numbers of the keys it sits on. */
    val keys: IntRange,
    /** Where along the speaking length, 0 at the agraffe, 1 at the bridge. */
    val position: Param = Param(0.25f),
    /** Added mass, in grams. */
    val mass: Param = Param(0f),
    /** Added loss, per second, at a mode's antinode. */
    val damping: Param = Param(0f),
    /** Level of the rattle; 0 is none, 1 is a loose nut. */
    val rattle: Param = Param(0f),
    /** Play either side of the rattling object, in millimetres. */
    val gap: Param = Param(0.05f),
    /** The rattling object's own ring, in hertz. */
    val tone: Param = Param(3200f),
    /** Which of the key's strings: 0, 1, 2, or -1 for every one. */
    val string: Int = -1,
) {
    internal fun render(b: Array<FloatArray>, n: Int, ctx: SynthContext) {
        position.render(b[POSITION], n, ctx); mass.render(b[MASS], n, ctx); damping.render(b[DAMPING], n, ctx)
        rattle.render(b[RATTLE], n, ctx); gap.render(b[GAP], n, ctx); tone.render(b[TONE], n, ctx)
        pianoFinite(b[POSITION], n, 0.25f); pianoFinite(b[MASS], n, 0f); pianoFinite(b[DAMPING], n, 0f)
        pianoFinite(b[RATTLE], n, 0f, limit = 1e6f); pianoFinite(b[GAP], n, 0.05f); pianoFinite(b[TONE], n, 3200f)
    }

    companion object {
        internal const val POSITION = 0
        internal const val MASS = 1
        internal const val DAMPING = 2
        internal const val RATTLE = 3
        internal const val GAP = 4
        internal const val TONE = 5
        internal const val INPUTS = 6

        /** A bolt of [grams] at [at] on [keys]: the partials break into a gong-like chord. */
        fun bolt(keys: IntRange, at: Double = 0.3, grams: Double = 6.0, rattle: Double = 0.0) =
            PianoPreparation(keys, position = Param(at.toFloat()), mass = Param(grams.toFloat()), rattle = Param(rattle.toFloat()))

        /** A rubber wedge at [at] on [keys]: the modes that move there die away in a fraction of a second. */
        fun rubber(keys: IntRange, at: Double = 0.12, amount: Double = 25.0) =
            PianoPreparation(keys, position = Param(at.toFloat()), damping = Param(amount.toFloat()), mass = Param(0.5f))

        /** A loose nut at [at] on [keys]: it buzzes at [tone] while the string swings wider than [gapMm]. */
        fun rattle(keys: IntRange, at: Double = 0.2, level: Double = 1.0, gapMm: Double = 0.05, tone: Double = 3200.0) =
            PianoPreparation(keys, position = Param(at.toFloat()), mass = Param(1f), rattle = Param(level.toFloat()), gap = Param(gapMm.toFloat()), tone = Param(tone.toFloat()))
    }
}

/**
 * One close microphone over a [Piano]'s strings, heard through [Piano.dry].
 *
 * [position] runs along the speaking length (0 at the agraffe, 1 at the bridge) and the microphone
 * hears each mode by `sin(nπx)` there. [pan] is -1 left to 1 right; at 0 it is in both channels at
 * full level. [string] listens to one of each key's strings (0, 1 or 2) and not the others, so two
 * microphones on different strings of the same notes, panned apart, put the beating between those
 * strings into the room instead of into one channel. A pair is recorded the way a studio records a
 * grand:
 *
 * ```kotlin
 * val piano = Piano(dry = Param(1f), room = Param(0.1f), mics = listOf(
 *     PianoMic(position = Param(0.08f), pan = Param(-0.8f)),
 *     PianoMic(position = Param(0.35f), pan = Param(0.8f)),
 * ))
 * ```
 */
class PianoMic(
    val position: Param = Param(0.12f),
    val pan: Param = Param(0f),
    val gain: Param = Param(1f),
    val string: Int = -1,
)

/**
 * Renders [clip] through [piano] to a stereo WAV at [wav], with [tailSeconds] after the last note
 * for the strings and room to ring out, peak-normalised to [normalizePeak] (null leaves the level).
 * Returns the render. Sustain, sostenuto and soft pedals in the clip are played.
 *
 * ```kotlin
 * pianoRenderMidi(readMidiClip(java.io.File("prelude.mid")), java.io.File("/tmp/prelude.wav"))
 * ```
 */
fun pianoRenderMidi(
    clip: MidiClip, wav: File, piano: Piano = Piano(sampleRate = 44_100), tailSeconds: Double = 3.0, normalizePeak: Float? = 0.89f,
): PianoRender {
    val r = pianoRender(clip.durationSeconds + tailSeconds, piano) { play(clip, startFrame = 0) }
    r.writeWav(wav, normalizePeak)
    return r
}

/** [pianoRenderMidi] from a `.mid` file. */
fun pianoRenderMidi(midi: File, wav: File, piano: Piano = Piano(sampleRate = 44_100), tailSeconds: Double = 3.0, normalizePeak: Float? = 0.89f): PianoRender =
    pianoRenderMidi(readMidiClip(midi), wav, piano, tailSeconds, normalizePeak)

/**
 * This stereo source as a mono [Signal], the average of its two channels, so a sampler, a Faust
 * patch or another [Piano] can drive a [Param]: `Piano(bridgeInput = Param(0f).modulate(other.toMono()))`
 * plays one instrument through the strings and soundboard of another.
 */
fun StereoRenderable.toMono(): Signal = StereoMono(this)

private class StereoMono(private val source: StereoRenderable) : Signal() {
    private var l = FloatArray(0); private var r = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (l.size < numFrames) { l = FloatArray(numFrames); r = FloatArray(numFrames) }
        source.render(l, r, numFrames, ctx)
        for (i in 0 until numFrames) output[i] = 0.5f * (l[i] + r[i])
    }
}

/**
 * The modes of a string carrying one point mass, exactly: the generalized eigenproblem
 * `K v = λ (I + c·s·sᵀ) v` in the unprepared string's modal coordinates, with `K` the diagonal of
 * squared mode frequencies, `s` each mode's shape `sin(nπx)` at the mass and `c = 2m/M`.
 *
 * A mass only lowers modes, and the k-th prepared mode stays above the (k−1)-th unprepared one: the
 * roots of the secular equation `1/(cλ) = Σ s²/(ω² − λ)` interlace with the unprepared `ω²`, one per
 * interval. A mode with a node at the mass keeps its frequency and its shape. The prepared modes
 * are mixtures of the unprepared ones, which changes how each is heard at the bridge.
 */
internal object PianoMassCoupling {
    /**
     * For [n] modes with increasing angular frequencies [w] and shapes [s] at the mass, writes each
     * prepared mode's frequency ratio to [fac] (prepared over unprepared, mode k for mode k) and, when
     * [vec] is given, its M-normalised shape in unprepared coordinates (`vec[k][j]`), signed so
     * `vec[k][k] > 0`. [guess] holds the previous roots (λ) to start from, and receives the new ones.
     * [c] must be positive.
     */
    fun solve(w: DoubleArray, s: DoubleArray, n: Int, c: Double, fac: DoubleArray, vec: Array<DoubleArray>?, guess: DoubleArray) {
        val coupled = IntArray(n); var nc = 0
        for (k in 0 until n) if (s[k] * s[k] > 1e-12) coupled[nc++] = k
        for (k in 0 until n) if (s[k] * s[k] <= 1e-12) {
            fac[k] = 1.0; guess[k] = w[k] * w[k]
            if (vec != null) { java.util.Arrays.fill(vec[k], 0, n, 0.0); vec[k][k] = 1.0 }
        }
        var prev = 0.0
        for (i in 0 until nc) {
            val k = coupled[i]
            val hi = w[k] * w[k]
            var lo = prev
            prev = hi
            var up = hi
            var lam = guess[k]
            if (!(lam > lo && lam < up)) {
                val diag = hi / (1.0 + c * s[k] * s[k])
                lam = if (diag > lo && diag < up) diag else 0.5 * (lo + up)
            }
            for (iter in 0 until 80) {
                var f = 1.0 / (c * lam); var df = -1.0 / (c * lam * lam)
                for (jj in 0 until nc) {
                    val j = coupled[jj]
                    val d = w[j] * w[j] - lam
                    val s2 = s[j] * s[j]
                    f -= s2 / d; df -= s2 / (d * d)
                }
                if (f > 0) lo = lam else up = lam
                var next = lam - f / df
                if (!(next > lo && next < up)) next = 0.5 * (lo + up)
                val done = abs(next - lam) <= 1e-14 * hi
                lam = next
                if (done || up - lo <= 1e-15 * hi) break
            }
            guess[k] = lam
            fac[k] = sqrt(lam) / w[k]
            if (vec != null) {
                val v = vec[k]
                java.util.Arrays.fill(v, 0, n, 0.0)
                var sv = 0.0; var vv = 0.0
                for (jj in 0 until nc) { val j = coupled[jj]; val x = s[j] / (w[j] * w[j] - lam); v[j] = x; sv += s[j] * x; vv += x * x }
                var norm = sqrt(vv + c * sv * sv)
                if (!(norm > 0) || !norm.isFinite()) { java.util.Arrays.fill(v, 0, n, 0.0); v[k] = 1.0; continue }
                if (v[k] < 0) norm = -norm
                for (jj in 0 until nc) { val j = coupled[jj]; v[j] /= norm }
            }
        }
    }
}
