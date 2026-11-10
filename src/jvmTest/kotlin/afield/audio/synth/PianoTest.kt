package afield.audio.synth

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PianoTest {
    /** Set PIANO_TEST_DUMP=1 to write the renders the C++ comparison reads into build/piano-test. */
    private val dump = System.getenv("PIANO_TEST_DUMP") == "1"
    private val out = File("build/piano-test").also { if (dump) it.mkdirs() }

    /** Reference values printed by libc++'s std::mt19937_64{42} and its distributions. */
    @Test
    fun rngReproducesLibcxx() {
        val r = PianoRng(42)
        assertEquals("13930160852258120406", r.nextLong().toULong().toString())
        assertEquals(0.27806278770939485, r.uniform(-1.0, 1.0))
        val g = r.Normal()
        assertEquals(0.39797739618378886, g.next(), 1e-15)
        assertEquals(-0.57409480672026159, g.next(), 1e-15)
        assertEquals(1.1185550524574792, g.next(), 1e-15)
    }

    /**
     * The hammer contact and phasor bank against coopiano's own `render_bridge_force` (seed 1,
     * velocity 0.5, Apple clang 17, -O2): contact time, peak force and phasor count per key.
     */
    @Test
    fun bridgeForceMatchesCoopianoPreparation() {
        val reference = mapOf(69 to Triple(1.973, 9.31, 186), 33 to Triple(3.715, 17.10, 1112), 84 to Triple(1.142, 14.94, 84))
        for (midi in intArrayOf(69, 33, 84)) {
            val key = PianoKey(midi.toDouble(), PianoVoicing())
            val d = PianoStrike.prepare(key, PianoString.forKey(key), 0.5, 1L, 44_100.0)
            println("PIANO prep $midi contact ${"%.3f".format(d.contactMs)} ms peak ${"%.2f".format(d.peakForce)} N vh ${"%.3f".format(d.hammerVelocity)} phasors ${d.count}")
            val (ms, force, count) = reference.getValue(midi)
            assertEquals(ms, d.contactMs, 0.0005); assertEquals(force, d.peakForce, 0.005); assertEquals(count, d.count)
            if (!dump) continue
            val (f, _) = pianoBridgeForce(midi, 0.5, 8.0)
            java.io.DataOutputStream(File(out, "bridge_$midi.f64").outputStream().buffered()).use { o ->
                val bb = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (x in f) { bb.clear(); bb.putDouble(x); o.write(bb.array()) }
            }
        }
    }

    @Test
    fun a4RendersAndWritesWav() {
        val t0 = System.nanoTime()
        val r = pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(69, 0.5, 0); noteOff(69, 8L * 44_100) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("PIANO A4 render ${"%.0f".format(ms)} ms for 8 s, peak ${r.peak}")
        assertTrue(r.peak > 0.01f && r.peak < 1f, "peak ${r.peak}")
        if (!dump) return
        r.writeWav(File(out, "A4_field.wav"))
        pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f), coupling = Param(0f))) { noteOn(69, 0.5, 0) }.writeWav(File(out, "A4_nores.wav"))
    }

    private fun rms(x: FloatArray, from: Double, to: Double, sr: Int = 44_100): Double {
        var e = 0.0; val a = (from * sr).toInt(); val b = (to * sr).toInt()
        for (i in a until b) e += x[i].toDouble() * x[i]
        return 10 * log10(e / (b - a) + 1e-30)
    }

    @Test
    fun outputIsTheSameAtAnyBlockSize() {
        fun render(block: Int) = pianoRender(1.5, Piano(sampleRate = 44_100), blockFrames = block) {
            noteOn(60, 0.6, 1000); noteOn(64, 0.4, 1003); noteOff(60, 30_000); pedalEvent(1.0, 20_000)
        }
        val a = render(64); val b = render(256); val c = render(1000)
        kotlin.test.assertContentEquals(a.left, b.left); kotlin.test.assertContentEquals(a.left, c.left)
    }

    @Test
    fun damperStopsTheStringAndThePedalHoldsIt() {
        val up = pianoRender(3.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(60, 0.6, 0); noteOff(60, 22_050) }
        val down = pianoRender(3.0, Piano(sampleRate = 44_100, room = Param(0f), pedal = Param(1f))) { noteOn(60, 0.6, 0); noteOff(60, 22_050) }
        val dUp = rms(up.mono, 0.3, 0.45) - rms(up.mono, 2.0, 2.5)
        val dDown = rms(down.mono, 0.3, 0.45) - rms(down.mono, 2.0, 2.5)
        println("PIANO damper: drop after release with pedal up ${"%.1f".format(dUp)} dB, down ${"%.1f".format(dDown)} dB")
        assertTrue(dUp > 40.0, "damper drop $dUp")
        assertTrue(dDown < 20.0, "pedal drop $dDown")
    }

    @Test
    fun pedalLetsTheSympatheticStringsRing() {
        // Drive the 88-key resonator bank with C4's pitch for half a second, then stop: with the
        // dampers lifted the strings keep sounding; with them seated they stop within ~0.2 s.
        fun tail(pedal: Double): Double {
            val r = PianoResonance(PianoVoicing(), 44_100.0)
            val own = Array(88) { DoubleArray(1) }; val active = BooleanArray(88)
            val f = PianoMath.midiToFreq(60.0)
            var e = 0.0
            for (i in 0 until 44_100) {
                val x = if (i < 22_050) Math.sin(2 * Math.PI * f * i / 44_100) else 0.0
                val w = r.process(x, pedal, own, active, 0)
                if (i >= 33_075) e += w * w
            }
            return 10 * log10(e + 1e-30)
        }
        val up = tail(0.0); val down = tail(1.0)
        println("PIANO resonance tail 0.25-0.5 s after the drive stops: pedal up ${"%.1f".format(up)} dB, down ${"%.1f".format(down)} dB")
        assertTrue(down > up + 30.0)
    }

    @Test
    fun hardHammersAreBrighter() {
        // Energy above 2 kHz against the whole, over the first 0.37 s of middle C.
        fun upper(h: Float): Double {
            val r = pianoRender(0.6, Piano(sampleRate = 44_100, room = Param(0f), hardness = Param(h))) { noteOn(60, 0.6, 0) }
            val spec = pianoSpectrumDb(r.mono, 0, 16384)
            var hi = 0.0; var all = 0.0
            for (i in spec.indices) { val p = Math.pow(10.0, spec[i] / 10); all += p; if (i * 44_100.0 / 16384 > 2000) hi += p }
            return 10 * log10(hi / all)
        }
        val soft = upper(0.3f); val hard = upper(3f)
        println("PIANO energy above 2 kHz, hardness 0.3: ${"%.1f".format(soft)} dB, 3.0: ${"%.1f".format(hard)} dB")
        assertTrue(hard > soft + 3.0)
    }

    @Test
    fun inharmonicityMovesRingingPartialsAtAudioRate() {
        // Stiffen the strings of a held note while it rings: its upper partials must move up.
        fun peakNear(x: FloatArray, hz: Double): Double {
            val spec = pianoSpectrumDb(x, (1.0 * 44_100).toInt(), 32768)
            val bin = hz * 32768 / 44_100
            var best = -1; for (i in (bin * 0.97).toInt()..(bin * 1.06).toInt()) if (best < 0 || spec[i] > spec[best]) best = i
            return best * 44_100.0 / 32768
        }
        val base = pianoRender(2.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(45, 0.7, 0) }
        val stiff = pianoRender(2.0, Piano(sampleRate = 44_100, room = Param(0f), inharmonicity = Param(1f).also { p -> p.supplier = { if (time > 0.5f) 8f else 1f } })) { noteOn(45, 0.7, 0) }
        val f0 = PianoMath.midiToFreq(45.0)
        val p10 = peakNear(base.mono, 10 * f0); val q10 = peakNear(stiff.mono, 10 * f0)
        println("PIANO A2 partial 10: ${"%.1f".format(p10)} Hz -> ${"%.1f".format(q10)} Hz with inharmonicity x8 after 0.5 s")
        assertTrue(q10 > p10 * 1.005)
        // An audio-rate wobble keeps the voice finite and audible.
        val fm = pianoRender(1.0, Piano(sampleRate = 44_100, room = Param(0f), inharmonicity = Param(1f).modulate(SineOsc(freq = Param(220f)) * 0.8f))) { noteOn(45, 0.7, 0) }
        assertTrue(fm.left.all { it.isFinite() } && fm.peak > 0.01f)
    }
}
