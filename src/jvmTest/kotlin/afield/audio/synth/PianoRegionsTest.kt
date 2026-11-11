package afield.audio.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/** coopiano's four bridge-region soundboards. */
class PianoRegionsTest {
    private val sr = 44_100
    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size): Double { var e = 0.0; for (i in from until to) e += x[i].toDouble() * x[i]; return e }

    /** Regions that neither pan nor colour are the shared board, split into heads and a tail: the same output. */
    @Test
    fun flatRegionsAreTheSharedBoard() {
        val shared = PianoVoicing(boardSpread = 0.0, boardPositionDepth = 0.0)
        val flat = PianoVoicing(boardSpread = 0.0, boardPositionDepth = 1e-12)
        fun r(v: PianoVoicing) = pianoRender(1.5, Piano(sampleRate = sr, voicing = v, room = Param(0f), pedal = Param(1f), bridgeInput = Param(0f).also { it.supplier = { if (time < 0.01f) 1f else 0f } })) {
            noteOn(33, 0.8, 0); noteOn(69, 0.6, 500); noteOn(96, 0.6, 1000)
        }
        val a = r(shared); val b = r(flat)
        var err = 0.0; for (i in a.left.indices) err = maxOf(err, abs(a.left[i] - b.left[i]).toDouble(), abs(a.right[i] - b.right[i]).toDouble())
        println("PIANO regions: flat regions against the shared board, max difference ${"%.2e".format(err)} (peak ${a.peak})")
        assertTrue(err < 1e-5 * a.peak)
    }

    /** With the default spread the bass sits left and the treble right, as the player hears it. */
    @Test
    fun bassLeftTrebleRight() {
        fun balance(midi: Int, v: PianoVoicing = PianoVoicing()): Double {
            val r = pianoRender(1.0, Piano(sampleRate = sr, voicing = v, room = Param(0f))) { noteOn(midi, 0.7, 0) }
            return 10 * log10(energy(r.left) / energy(r.right))
        }
        val shared = PianoVoicing(boardSpread = 0.0, boardPositionDepth = 0.0)
        val keys = intArrayOf(33, 48, 60, 72, 84, 96)
        val reg = keys.map { balance(it) }; val sh = keys.map { balance(it, shared) }
        println("PIANO regions: left/right energy (dB) by key ${keys.toList()}: regions ${reg.map { "%.1f".format(it) }}, shared ${sh.map { "%.1f".format(it) }}")
        assertTrue(reg.first() - sh.first() > 1.0, "bass moves left")
        assertTrue(reg.last() - sh.last() < -1.0, "treble moves right")
    }

    /** Position depth colours the board differently along the bridge: neighbouring keys in two regions differ more than in one. */
    @Test
    fun positionDepthColoursByRegion() {
        fun spec(midi: Int, v: PianoVoicing) = pianoRender(1.0, Piano(sampleRate = sr, voicing = v, room = Param(0f))) { noteOn(midi, 0.7, 0) }.mono.let { pianoSpectrumDb(it, 0, 16384) }
        val coloured = PianoVoicing(boardSpread = 0.0, boardPositionDepth = 1.0); val flat = PianoVoicing(boardSpread = 0.0, boardPositionDepth = 0.0)
        var d = 0.0; val a = spec(60, coloured); val b = spec(60, flat)
        for (i in 200 until 4000) d += abs(a[i] - b[i])
        println("PIANO regions: C4 spectrum with position depth 1 against none, mean |difference| ${"%.2f".format(d / 3800)} dB over 0.5-11 kHz")
        assertTrue(d / 3800 > 0.3)
    }
}
