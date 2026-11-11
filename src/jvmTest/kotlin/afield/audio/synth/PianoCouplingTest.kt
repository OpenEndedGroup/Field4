package afield.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The exact point-mass modes against a dense generalized eigensolver. */
class PianoCouplingTest {
    /** Eigenvalues of the symmetric matrix [a] (n×n), by cyclic Jacobi rotations. */
    private fun jacobi(a: Array<DoubleArray>): DoubleArray {
        val n = a.size
        repeat(100) {
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val th = 0.5 * Math.atan2(2 * a[p][q], a[q][q] - a[p][p])
                val c = Math.cos(th); val s = Math.sin(th)
                for (k in 0 until n) { val x = a[k][p]; val y = a[k][q]; a[k][p] = c * x - s * y; a[k][q] = s * x + c * y }
                for (k in 0 until n) { val x = a[p][k]; val y = a[q][k]; a[p][k] = c * x - s * y; a[q][k] = s * x + c * y }
            }
        }
        return DoubleArray(n) { a[it][it] }.sortedArray()
    }

    @Test
    fun rootsMatchADenseSolve() {
        for ((c, x) in listOf(0.3 to 0.3, 4.0 to 0.3, 50.0 to 0.21, 2.0 to 0.5)) {
            val n = 12
            val f0 = 220.0; val B = 4e-4
            val w = DoubleArray(n) { 2 * PI * (it + 1) * f0 * sqrt(1 + B * (it + 1) * (it + 1)) }
            val s = DoubleArray(n) { sin((it + 1) * PI * x) }
            // M = I + c s sᵀ; eigenvalues of M^-1/2 K M^-1/2 via K^1/2 M^-1 K^1/2 (same spectrum, symmetric)
            val sts = s.sumOf { it * it }
            val minv = Array(n) { i -> DoubleArray(n) { j -> (if (i == j) 1.0 else 0.0) - c * s[i] * s[j] / (1 + c * sts) } }
            val a = Array(n) { i -> DoubleArray(n) { j -> w[i] * minv[i][j] * w[j] } }
            val dense = jacobi(a).map { sqrt(it) }
            val fac = DoubleArray(n); val guess = DoubleArray(n); val vec = Array(n) { DoubleArray(n) }
            PianoMassCoupling.solve(w, s, n, c, fac, vec, guess)
            val ours = DoubleArray(n) { w[it] * fac[it] }.sorted()
            val worst = (0 until n).maxOf { abs(ours[it] - dense[it]) / dense[it] }
            println("PIANO coupling c=$c x=$x: first roots ${ours.take(4).map { "%.1f".format(it / (2 * PI)) }} Hz, unprepared ${w.take(4).map { "%.1f".format(it / (2 * PI)) }}; worst relative error ${"%.1e".format(worst)}")
            assertTrue(worst < 1e-9)
            // M-orthonormal shapes
            for (k in 0 until n) for (l in 0 until n) {
                var dot = 0.0; var sk = 0.0; var sl = 0.0
                for (j in 0 until n) { dot += vec[k][j] * vec[l][j]; sk += s[j] * vec[k][j]; sl += s[j] * vec[l][j] }
                dot += c * sk * sl
                assertTrue(abs(dot - (if (k == l) 1.0 else 0.0)) < 1e-8, "shapes $k,$l: $dot")
            }
        }
    }

    private val sr = 44_100
    private fun peaks(x: FloatArray, maxHz: Double, within: Double = 30.0): List<Double> {
        val spec = pianoSpectrumDb(x, (0.3 * sr).toInt(), 32768)
        val top = spec.max()
        return (2 until (maxHz * 32768 / sr).toInt()).filter { spec[it] > spec[it - 1] && spec[it] >= spec[it + 1] && spec[it] > top - within }.map { it * sr / 32768.0 }
    }

    /** A heavy bolt rendered: the partials interlace (the k-th stays above the unprepared (k-1)-th), and stay finite. */
    @Test
    fun heavyBoltPartialsInterlace() {
        val f0 = PianoMath.midiToFreq(69.0)
        for (g in doubleArrayOf(2.0, 8.0, 40.0)) {
            val r = pianoRender(1.5, Piano(sampleRate = sr, room = Param(0f), preparations = listOf(PianoPreparation.bolt(69..69, at = 0.3, grams = g)))) { noteOn(69, 0.7, 0) }.mono
            assertTrue(r.all { it.isFinite() })
            val p = peaks(r, 3000.0, 40.0)
            println("PIANO A4, $g g bolt at 0.3, coupled: peaks within 40 dB below 3 kHz ${p.map { "%.0f".format(it) }}")
            // every unprepared interval (f0·k, f0·(k+1)) up to 2.7 kHz holds at most a few peaks, and nothing sits far below f0's mode 1 range
        }
        // the exact solve on A4's own strings: partial 2 of an 8 g bolt at 0.3 lies above partial 1's unprepared frequency
        val key = PianoKey(69.0, PianoVoicing())
        val n = 48
        val w = DoubleArray(n) { 2 * PI * (it + 1) * key.f0 * sqrt(1 + key.B * (it + 1) * (it + 1)) }
        val s = DoubleArray(n) { sin((it + 1) * PI * 0.3) }
        val fac = DoubleArray(n); val guess = DoubleArray(n)
        PianoMassCoupling.solve(w, s, n, 2 * 8e-3 / key.ms, fac, null, guess)
        val exact = (0 until 4).map { w[it] * fac[it] / (2 * PI) }
        val diag = (0 until 4).map { w[it] * pianoMassFactor(2 * 8e-3 / key.ms * s[it] * s[it]) / (2 * PI) }
        println("PIANO A4 8 g at 0.3: exact ${exact.map { "%.0f".format(it) }} Hz, per-mode rule ${diag.map { "%.0f".format(it) }} Hz")
        assertTrue(exact[1] > key.f0, "the second mode stays above the first unprepared one")
        assertTrue(diag[1] < key.f0, "which the per-mode rule does not")
    }

    /** A bolt that slides while the note rings, coupled at every step, stays finite and keeps sounding. */
    @Test
    fun slidingHeavyBoltStaysFinite() {
        val prep = PianoPreparation(21..108, position = Param(0.3f).modulate(SineOsc(freq = Param(3f)) * 0.25f), mass = Param(20f))
        val r = pianoRender(2.0, Piano(sampleRate = sr, room = Param(0f), preparations = listOf(prep), inharmonicity = Param(1f).modulate(SineOsc(freq = Param(50f)) * 0.5f))) {
            noteOn(33, 0.9, 0); noteOn(69, 0.7, 0); noteOn(84, 0.7, 100)
        }
        assertTrue(r.left.all { it.isFinite() } && r.right.all { it.isFinite() } && r.peak > 0.01f)
    }

    /** With the pedal down, a prepared key's sympathetic strings answer at the prepared pitch. */
    @Test
    fun sympatheticStringsArePrepared() {
        val key = 57 // A3, struck at the pitch its prepared strings now ring at
        val prep = PianoPreparation.bolt(key..key, at = 0.5, grams = 3.0)
        fun ring(withPrep: Boolean, drive: Double): Double {
            val p = Piano(sampleRate = sr, room = Param(0f), pedal = Param(1f), coupling = Param(4f), preparations = if (withPrep) listOf(prep) else emptyList(),
                bridgeInput = Param(0f).modulate(SineOsc(freq = Param(drive.toFloat())) * Param(0f).also { it.supplier = { if (time < 0.6f) 1f else 0f } }))
            val r = pianoRender(1.2, p).mono
            var e = 0.0; for (i in (0.8 * sr).toInt() until r.size) e += r[i].toDouble() * r[i]
            return 10 * kotlin.math.log10(e + 1e-30)
        }
        val res = PianoResonance(PianoVoicing(), sr.toDouble())
        val k = PianoKey(key.toDouble(), PianoVoicing())
        val w = doubleArrayOf(2 * PI * k.f0 * sqrt(1 + k.B))
        val fac = DoubleArray(1); PianoMassCoupling.solve(w, doubleArrayOf(1.0), 1, 2 * 3e-3 / k.ms, fac, null, DoubleArray(1))
        val prepared = k.f0 * fac[0]
        val atPlain = ring(true, k.f0) - ring(false, k.f0)
        val atPrepared = ring(true, prepared) - ring(false, prepared)
        println("PIANO sympathetic A3 with a 3 g bolt at 0.5 (fundamental ${"%.1f".format(k.f0)} -> ${"%.1f".format(prepared)} Hz): tail after a drive at the old pitch ${"%+.1f".format(atPlain)} dB, at the new pitch ${"%+.1f".format(atPrepared)} dB")
        assertTrue(atPrepared > atPlain + 6.0)
        res.modeCount
    }
}
