package afield.audio.synth

import afield.runtime.execution.plus
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

    /** The demo's pattern piece and pedal example as .wav files, for listening and analysis. */
    @Test
    fun demoWavs() {
        if (!dump) return
        val tune = pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]")
        pianoRender(11.0, Piano(sampleRate = 44_100)) { play(tune, cycles = 8, cps = 0.8, startFrame = 0) }.writeWav(File(out, "piece.wav"), 0.89f)
        val arpeggio = pat("[c2 g2 e3 c4 g4 e5 ~ ~]")
        for ((name, pedal) in listOf("pedal_up" to 0f, "pedal_down" to 1f))
            pianoRender(5.0, Piano(sampleRate = 44_100, pedal = Param(pedal))) { play(arpeggio, cycles = 1, cps = 0.5, legato = 0.15, startFrame = 0) }.writeWav(File(out, "$name.wav"))
    }

    /** `piano + piano2` is a launch bundle, not a mix; `audio(bundle)` turns each source in it into a patch. */
    @Test
    fun audioAcceptsALaunchBundleOfPianos() {
        val a = Piano(); val b = Piano()
        val bundle = audio(a + b, gain = 0.5f)
        assertEquals(2, bundle.items.size)
        assertTrue(bundle.items.all { it is AudioPatchSpec }, "every piano became its own patch: ${bundle.items}")
        val nested = audio(audio(a, gain = 1f) + b + Piano())
        assertEquals(3, nested.items.size)
        assertTrue(nested.items.all { it is AudioPatchSpec })
    }

    private fun sig(f: Float, depth: Float, centre: Float = 0f): Param = Param(centre).modulate(SineOsc(freq = Param(f)) * depth)

    /** No input, however wild, makes a NaN: negative, huge, infinite and NaN constants, and audio-rate sines. */
    @Test
    fun extremeInputsStayFinite() {
        val wild = listOf(-1e6f, -50f, -1f, 0f, 100f, 1e9f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)
        fun check(name: String, p: Piano) {
            val r = pianoRender(0.5, p) { noteOn(33, 1.0, 0); noteOn(69, 0.8, 0); noteOn(100, 0.6, 500); noteOff(69, 9000); noteOff(33, 15000) }
            val bad = r.left.count { !it.isFinite() } + r.right.count { !it.isFinite() }
            assertEquals(0, bad, "$name: $bad non-finite samples")
        }
        for (v in wild) {
            val c = Param(v)
            check("inharmonicity $v", Piano(sampleRate = 44_100, inharmonicity = Param(v)))
            check("decay $v", Piano(sampleRate = 44_100, decay = Param(v)))
            check("pedal $v", Piano(sampleRate = 44_100, pedal = Param(v)))
            check("soundboard $v", Piano(sampleRate = 44_100, soundboard = Param(v), coupling = Param(v)))
            check("close mic $v", Piano(sampleRate = 44_100, dry = Param(v), micPosition = Param(v), thump = Param(v), keyNoise = Param(v), damperNoise = Param(v), pinFundamental = c))
        }
        check("audio-rate everything", Piano(sampleRate = 44_100,
            inharmonicity = sig(310f, 400f), decay = sig(97f, 30f), pedal = sig(40f, 3f), soundboard = sig(523f, 1e4f),
            pinFundamental = sig(11f, 2f), micPosition = sig(880f, 3f), dry = sig(5f, 2f), thump = sig(200f, 50f), room = sig(3f, 20f)))
    }

    private fun peakNear(x: FloatArray, hz: Double, from: Double = 0.3, lo: Double = 0.97, hi: Double = 1.06): Pair<Double, Double> {
        val spec = pianoSpectrumDb(x, (from * 44_100).toInt(), 32768)
        val bin = hz * 32768 / 44_100
        var best = -1; for (i in (bin * lo).toInt()..(bin * hi).toInt()) if (best < 0 || spec[i] > spec[best]) best = i
        return best * 44_100.0 / 32768 to spec[best]
    }

    /** ×100 stiffness stretches partial 10 by more than a semitone; with the fundamental pinned, partial 1 stays put. */
    @Test
    fun wideStiffnessIsHeardAndCanKeepItsPitch() {
        val f0 = PianoMath.midiToFreq(45.0)
        fun render(s: Float, pin: Float) = pianoRender(1.5, Piano(sampleRate = 44_100, room = Param(0f), inharmonicity = Param(s), pinFundamental = Param(pin))) { noteOn(45, 0.7, 0) }.mono
        val base = render(1f, 0f); val stiff = render(100f, 0f); val pinned = render(100f, 1f); val soft = render(-100f, 0f)
        val p1 = peakNear(base, f0).first; val q1 = peakNear(stiff, f0, hi = 1.3).first; val r1 = peakNear(pinned, f0).first
        val p10 = peakNear(base, 10 * f0).first
        val q10 = peakNear(stiff, 10 * f0, hi = 2.5).first; val r10 = peakNear(pinned, 10 * f0, hi = 2.5).first; val s10 = peakNear(soft, 10 * f0, lo = 0.5, hi = 1.02).first
        println("PIANO A2 x100: partial 1 ${"%.1f".format(p1)} -> ${"%.1f".format(q1)} Hz (pinned ${"%.1f".format(r1)}); partial 10 ${"%.0f".format(p10)} -> ${"%.0f".format(q10)} (pinned ${"%.0f".format(r10)}, x-100 ${"%.0f".format(s10)})")
        assertTrue(q10 > p10 * 1.06, "x100 stretches partial 10 past a semitone")
        assertTrue(abs(r1 - p1) < 0.5, "pinned fundamental stays within half a hertz")
        assertTrue(r10 > p10 * 1.04, "pinned upper partials still stretch")
        assertTrue(s10 < p10 * 0.99, "negative stiffness compresses partials")
    }

    /** The close mic hears the string where it sits: at x = 1/2 the even partials are nodes. */
    @Test
    fun closeMicFollowsPickupPosition() {
        val f0 = PianoMath.midiToFreq(45.0)
        fun render(x: Float, dryMix: Float = 1f, th: Float = 0f, key: Float = 0f) =
            pianoRender(1.5, Piano(sampleRate = 44_100, room = Param(0f), dry = Param(dryMix), micPosition = Param(x), thump = Param(th), keyNoise = Param(key))) { noteOn(45, 0.7, 0) }.mono
        val board = render(0.12f, 0f); val near = render(0.12f); val mid = render(0.5f)
        fun level(x: FloatArray, n: Int) = peakNear(x, n * f0, lo = 0.99, hi = 1.03).second
        val drop2 = (level(near, 2) - level(near, 1)) - (level(mid, 2) - level(mid, 1))
        val drop3 = (level(near, 3) - level(near, 1)) - (level(mid, 3) - level(mid, 1))
        println("PIANO mic x=0.5 vs 0.12: partial 2 down ${"%.1f".format(drop2)} dB, partial 3 down ${"%.1f".format(drop3)} dB (relative to partial 1)")
        println("PIANO dry mic level ${"%.1f".format(rms(near, 0.05, 1.0))} dB vs soundboard ${"%.1f".format(rms(board, 0.05, 1.0))} dB")
        assertTrue(drop2 > 20.0, "partial 2 sits on a node at x = 1/2")
        assertTrue(drop2 > drop3 + 10.0, "odd partials do not")
        assertTrue(abs(rms(near, 0.05, 1.0) - rms(board, 0.05, 1.0)) < 6.0, "dry mic sits near the board's level")
        // thump and key noise add energy in the first 40 ms and leave the tail alone
        val noisy = render(0.12f, th = 1f, key = 1f)
        val early = rms(noisy, 0.0, 0.04) - rms(near, 0.0, 0.04)
        val late = rms(noisy, 0.5, 1.0) - rms(near, 0.5, 1.0)
        println("PIANO thump+key: first 40 ms +${"%.1f".format(early)} dB, 0.5-1 s +${"%.2f".format(late)} dB")
        assertTrue(early > 1.0 && abs(late) < 0.5)
    }

    /** Before/after .wav files of the close mic, for listening. */
    @Test
    fun closeMicWavs() {
        if (!dump) return
        val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
        fun r(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
        r(Piano(sampleRate = 44_100)).writeWav(File(out, "close_before.wav"), 0.89f)
        r(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0f), micPosition = Param(0.08f), thump = Param(1f), keyNoise = Param(1f), damperNoise = Param(2f))).writeWav(File(out, "close_after.wav"), 0.89f)
        r(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0f), micPosition = Param(0.5f), thump = Param(1f), keyNoise = Param(1f))).writeWav(File(out, "close_mid_string.wav"), 0.89f)
        r(Piano(sampleRate = 44_100, inharmonicity = Param(60f), pinFundamental = Param(1f))).writeWav(File(out, "stiff_x60_pinned.wav"), 0.89f)
        r(Piano(sampleRate = 44_100, dry = Param(0.7f), micPosition = sig(0.3f, 0.1f, 0.2f), inharmonicity = sig(180f, 40f, 1f))).writeWav(File(out, "close_moving_mic_fm.wav"), 0.89f)
    }
}
