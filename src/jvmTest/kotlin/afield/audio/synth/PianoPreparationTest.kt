package afield.audio.synth

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prepared strings, microphone arrays, the bridge input, the soft and sostenuto pedals, and strikes prepared ahead. */
class PianoPreparationTest {
    private val dump = System.getenv("PIANO_TEST_DUMP") == "1"
    private val out = File("build/piano-test").also { if (dump) it.mkdirs() }
    private val sr = 44_100

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        var e = 0.0; val a = (from * sr).toInt(); val b = min((to * sr).toInt(), x.size)
        for (i in a until b) e += x[i].toDouble() * x[i]
        return 10 * log10(e / (b - a) + 1e-30)
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    private fun peakNear(x: FloatArray, hz: Double, from: Double = 0.3, lo: Double = 0.97, hi: Double = 1.03): Pair<Double, Double> {
        val spec = pianoSpectrumDb(x, (from * sr).toInt(), 32768)
        val bin = hz * 32768 / sr
        var best = -1; for (i in (bin * lo).toInt()..(bin * hi).toInt()) if (best < 0 || spec[i] > spec[best]) best = i
        return best * sr.toDouble() / 32768 to spec[best]
    }

    private fun piano(vararg preps: PianoPreparation, ahead: Boolean = true) =
        Piano(sampleRate = sr, room = Param(0f), preparations = preps.toList(), prepareAhead = ahead)

    /** A strike prepared on a worker is the same strike: the output is identical sample for sample. */
    @Test
    fun strikesPreparedAheadAreTheSame() {
        fun render(ahead: Boolean, block: Int) = Piano(sampleRate = sr, prepareAhead = ahead).let { p ->
            pianoRender(2.0, p, blockFrames = block) {
                play(pat("[a1,e2,a2,c3] [e4 a4 c5 e5]"), cycles = 2, cps = 1.0, startFrame = 0); noteOn(30, 0.9, 30_000)
            } to p
        }
        val (a, pa) = render(false, 128); val (b, pb) = render(true, 128); val (c, _) = render(true, 777)
        println("PIANO ahead: inline strikes ${pa.strikesPreparedInline} without, ${pb.strikesPreparedInline} with workers")
        assertContentEquals(a.left, b.left); assertContentEquals(a.right, b.right); assertContentEquals(a.left, c.left)
        assertEquals(0L, pb.strikesPreparedInline)
    }

    /** On the live engine's clock: a bass chord queued a block ahead costs the audio thread little. */
    @Test
    fun bassChordCostOnTheAudioThread() {
        fun worst(ahead: Boolean): Double {
            val p = Piano(sampleRate = 48_000, prepareAhead = ahead)
            val ctx = SynthContext(48_000f, 128)
            val l = FloatArray(128); val r = FloatArray(128)
            repeat(20) { p.render(l, r, 128, ctx); ctx.cycleId++ }
            // as a live MIDI input would: six bass notes "now", while blocks are being rendered
            for (m in intArrayOf(21, 28, 33, 36, 40, 45)) p.noteOn(m, 0.9)
            var worst = 0.0
            repeat(60) {
                val t0 = System.nanoTime(); p.render(l, r, 128, ctx); ctx.cycleId++
                worst = maxOf(worst, (System.nanoTime() - t0) / 1e6)
            }
            return worst
        }
        worst(true); worst(false) // warm
        val inline = worst(false); val ahead = worst(true)
        println("PIANO worst 128-frame block (2.67 ms budget) after a six-note bass chord: inline ${"%.1f".format(inline)} ms, prepared ahead ${"%.1f".format(ahead)} ms")
        assertTrue(ahead < inline, "workers take the strike off the audio thread")
    }

    /** A bolt pulls each partial down by its own amount; a node under the bolt leaves that partial alone. */
    @Test
    fun boltDetunesPartialsByTheirShape() {
        val f0 = PianoMath.midiToFreq(69.0)
        val plain = pianoRender(1.5, piano()) { noteOn(69, 0.7, 0) }.mono
        // at x = 1/2, odd partials move and even ones (nodes) do not
        val bolted = pianoRender(1.5, piano(PianoPreparation.bolt(69..69, at = 0.5, grams = 2.0))) { noteOn(69, 0.7, 0) }.mono
        val p1 = peakNear(plain, f0).first; val b1 = peakNear(bolted, f0, lo = 0.5, hi = 1.01).first
        val p2 = peakNear(plain, 2 * f0).first; val b2 = peakNear(bolted, 2 * f0, lo = 0.99, hi = 1.01).first
        println("PIANO bolt 2 g at x=0.5 on A4: partial 1 ${"%.1f".format(p1)} -> ${"%.1f".format(b1)} Hz, partial 2 ${"%.1f".format(p2)} -> ${"%.1f".format(b2)} Hz")
        for (g in doubleArrayOf(2.0, 8.0)) {
            val r = pianoRender(1.5, piano(PianoPreparation.bolt(69..69, at = 0.3, grams = g))) { noteOn(69, 0.7, 0) }.mono
            val spec = pianoSpectrumDb(r, (0.3 * sr).toInt(), 32768)
            val peaks = (2 until 5000 * 32768 / sr).filter { spec[it] > spec[it - 1] && spec[it] >= spec[it + 1] && spec[it] > spec.max() - 30 }.map { "%.0f".format(it * sr / 32768.0) }
            println("PIANO A4 with a $g g bolt at x = 0.3: peaks within 30 dB of the top below 5 kHz: $peaks")
        }
        assertTrue(b1 < p1 * 0.85, "partial 1 sits under the bolt and drops")
        assertTrue(abs(b2 - p2) < 1.0, "partial 2 has a node there")
    }

    /** Rubber damps the modes that move under it and spares the ones with a node there. */
    @Test
    fun rubberMutesWhereItSits() {
        val f0 = PianoMath.midiToFreq(57.0)
        val plain = pianoRender(1.5, piano()) { noteOn(57, 0.7, 0) }.mono
        val muted = pianoRender(1.5, piano(PianoPreparation(57..57, position = Param(0.5f), damping = Param(30f)))) { noteOn(57, 0.7, 0) }.mono
        val d1 = peakNear(plain, f0, from = 0.8).second - peakNear(muted, f0, from = 0.8).second
        val d2 = peakNear(plain, 2 * f0, from = 0.8).second - peakNear(muted, 2 * f0, from = 0.8).second
        println("PIANO rubber at x=0.5 on A3, 0.8 s in: partial 1 down ${"%.1f".format(d1)} dB, partial 2 down ${"%.1f".format(d2)} dB")
        assertTrue(d1 > 20.0 && d2 < 3.0)
        // negative damping holds a mode up instead of letting it grow without bound
        val drone = pianoRender(3.0, piano(PianoPreparation(57..57, position = Param(0.3f), damping = Param(-1e5f)))) { noteOn(57, 0.7, 0) }
        assertTrue(drone.left.all { it.isFinite() })
        println("PIANO negative damping: 0.5 s ${"%.1f".format(rms(drone.mono, 0.4, 0.6))} dB, 2.8 s ${"%.1f".format(rms(drone.mono, 2.7, 2.9))} dB")
    }

    /** A rattle buzzes while the string swings wider than its gap: a narrow gap buzzes on, a wide one stops as the note decays. */
    @Test
    fun rattleBuzzesWhileTheStringSwingsWiderThanItsGap() {
        fun hiBand(x: FloatArray, t: Double): Double {
            val spec = pianoSpectrumDb(x, (t * sr).toInt(), 8192)
            var e = 0.0; for (i in spec.indices) if (i * sr / 8192.0 in 2500.0..6000.0) e += Math.pow(10.0, spec[i] / 10)
            return 10 * log10(e + 1e-30)
        }
        val times = doubleArrayOf(0.3, 1.5, 3.6)
        val buzz = HashMap<Double, DoubleArray>()
        for (gap in doubleArrayOf(0.05, 0.5, 2.0)) {
            val plain = pianoRender(4.0, piano(PianoPreparation.rattle(45..45, at = 0.2, level = 0.0, gapMm = gap))) { noteOn(45, 0.9, 0) }.mono
            val r = pianoRender(4.0, piano(PianoPreparation.rattle(45..45, at = 0.2, level = 1.0, gapMm = gap))) { noteOn(45, 0.9, 0) }.mono
            buzz[gap] = DoubleArray(times.size) { hiBand(r, times[it]) - hiBand(plain, times[it]) }
            val diff = FloatArray(r.size) { r[it] - plain[it] }
            println("PIANO rattle alone, gap $gap mm, against the note: 0-0.5 s ${"%.1f".format(rms(diff, 0.0, 0.5) - rms(plain, 0.0, 0.5))} dB, 1-2 s ${"%.1f".format(rms(diff, 1.0, 2.0) - rms(plain, 1.0, 2.0))} dB")
            println("PIANO rattle on A2, gap $gap mm: 2.5-6 kHz over the same note with a silent rattle " + times.indices.joinToString { "%.1f s %+.1f dB".format(times[it], buzz[gap]!![it]) })
        }
        assertTrue(buzz[0.05]!![1] > 6.0 && buzz[0.05]!![2] > 6.0, "a narrow gap buzzes on")
        val wide = buzz[2.0]!!
        assertTrue(wide[1] > 6.0 && abs(wide[2]) < 1.0, "a wide gap buzzes, then stops as the swing falls inside it")
    }

    /** Preparations take any value, at audio rate, and stay finite. */
    @Test
    fun wildPreparationsStayFinite() {
        val wild = listOf(-1e6f, -3f, 0f, 0.5f, 7f, 1e9f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)
        for (v in wild) {
            val p = piano(PianoPreparation(21..108, position = Param(v), mass = Param(v), damping = Param(v), rattle = Param(v), gap = Param(v), tone = Param(v)))
            val r = pianoRender(0.4, p) { noteOn(33, 1.0, 0); noteOn(69, 0.8, 0); noteOn(100, 0.6, 500); noteOff(69, 9000) }
            assertEquals(0, r.left.count { !it.isFinite() } + r.right.count { !it.isFinite() }, "preparation at $v")
        }
        fun sig(f: Float, depth: Float, c: Float = 0f) = Param(c).modulate(SineOsc(freq = Param(f)) * depth)
        val moving = piano(PianoPreparation(21..108, position = sig(300f, 2f), mass = sig(77f, 50f), damping = sig(13f, 100f), rattle = sig(5f, 3f), gap = sig(40f, 0.1f), tone = sig(2f, 5000f, 3000f)))
        val r = pianoRender(0.6, moving) { noteOn(33, 1.0, 0); noteOn(69, 0.8, 0) }
        assertTrue(r.left.all { it.isFinite() } && r.peak > 0.001f)
    }

    /** Two microphones panned apart: the left hears its own position, and a per-string pickup hears one string. */
    @Test
    fun micPairAndPerStringPickups() {
        val f0 = PianoMath.midiToFreq(45.0)
        val pair = pianoRender(1.5, Piano(sampleRate = sr, room = Param(0f), dry = Param(1f), mics = listOf(
            PianoMic(position = Param(0.5f), pan = Param(-1f)), PianoMic(position = Param(0.12f), pan = Param(1f)),
        ))) { noteOn(45, 0.7, 0) }
        fun rel2(x: FloatArray) = peakNear(x, 2 * f0, lo = 0.99).second - peakNear(x, f0, lo = 0.99, hi = 1.01).second
        println("PIANO mic pair: partial 2 vs 1, left (x=0.5) ${"%.1f".format(rel2(pair.left))} dB, right (x=0.12) ${"%.1f".format(rel2(pair.right))} dB")
        assertTrue(rel2(pair.right) > rel2(pair.left) + 20.0)
        // one centred mic in the list is the default microphone
        val one = pianoRender(0.5, Piano(sampleRate = sr, dry = Param(1f), mics = listOf(PianoMic(position = Param(0.12f))))) { noteOn(45, 0.7, 0) }
        val dflt = pianoRender(0.5, Piano(sampleRate = sr, dry = Param(1f))) { noteOn(45, 0.7, 0) }
        assertContentEquals(dflt.left, one.left)
        // A4 has three strings; a pickup on one alone does not beat
        fun beat(x: FloatArray): Double {
            val env = DoubleArray(40) { k -> rms(x, 0.5 + k * 0.05, 0.55 + k * 0.05) }
            return env.maxOrNull()!! - env.minOrNull()!!
        }
        val all = pianoRender(2.5, Piano(sampleRate = sr, room = Param(0f), dry = Param(1f))) { noteOn(69, 0.6, 0) }.mono
        val single = pianoRender(2.5, Piano(sampleRate = sr, room = Param(0f), dry = Param(1f), mics = listOf(PianoMic(string = 0)))) { noteOn(69, 0.6, 0) }.mono
        println("PIANO A4 envelope ripple 0.5-2.5 s: all strings ${"%.1f".format(beat(all))} dB, string 0 alone ${"%.1f".format(beat(single))} dB")
        assertTrue(beat(single) < beat(all))
    }

    /** A signal on the bridge sounds through the board; with the pedal down the strings keep ringing after it stops. */
    @Test
    fun bridgeInputDrivesTheStrings() {
        val f = PianoMath.midiToFreq(48.0)
        fun burst(pedal: Float) = pianoRender(2.0, Piano(sampleRate = sr, room = Param(0f), pedal = Param(pedal),
            bridgeInput = Param(0f).also { it.supplier = { if (time < 0.5f) 1f else 0f } }.let { gate -> Param(0f).modulate(SineOsc(freq = Param(f.toFloat())) * gate) })).mono
        val up = burst(0f); val down = burst(1f)
        val note = pianoRender(1.0, Piano(sampleRate = sr, room = Param(0f))) { noteOn(48, 0.6, 0) }.mono
        println("PIANO bridge input sine at C3: during ${"%.1f".format(rms(up, 0.1, 0.45))} dB (C3 mf note ${"%.1f".format(rms(note, 0.1, 0.45))} dB); 0.3 s after it stops, pedal up ${"%.1f".format(rms(up, 0.8, 1.0))} dB, down ${"%.1f".format(rms(down, 0.8, 1.0))} dB")
        assertTrue(rms(down, 0.8, 1.0) > rms(up, 0.8, 1.0) + 15.0)
    }

    /** Una corda: the hammer misses one string and meets softer felt, so the note is quieter and darker. */
    @Test
    fun unaCordaIsSofterAndDarker() {
        fun bright(x: FloatArray): Double {
            val spec = pianoSpectrumDb(x, 0, 16384)
            var hi = 0.0; var all = 0.0
            for (i in spec.indices) { val p = Math.pow(10.0, spec[i] / 10); all += p; if (i * sr / 16384.0 > 2000) hi += p }
            return 10 * log10(hi / all)
        }
        val open = pianoRender(1.0, Piano(sampleRate = sr, room = Param(0f))) { noteOn(64, 0.7, 0) }.mono
        val soft = pianoRender(1.0, Piano(sampleRate = sr, room = Param(0f), unaCorda = Param(1f))) { noteOn(64, 0.7, 0) }.mono
        println("PIANO una corda on E4: level ${"%.1f".format(rms(soft, 0.0, 1.0) - rms(open, 0.0, 1.0))} dB, energy above 2 kHz ${"%.1f".format(bright(open))} -> ${"%.1f".format(bright(soft))} dB")
        assertTrue(rms(soft, 0.0, 1.0) < rms(open, 0.0, 1.0) - 1.0)
        assertTrue(bright(soft) < bright(open) - 1.0)
    }

    /** Sostenuto holds the keys down when it is pressed and no others. */
    @Test
    fun sostenutoHoldsOnlyTheKeysAlreadyDown() {
        val r = pianoRender(3.0, Piano(sampleRate = sr, room = Param(0f))) {
            noteOn(48, 0.7, 0); sostenutoEvent(1.0, 4_410); noteOff(48, 8_820)  // held by the sostenuto
            noteOn(73, 0.7, 13_230); noteOff(73, 22_050)                          // pressed after it: damped
        }
        val held = pianoRender(3.0, Piano(sampleRate = sr, room = Param(0f))) { noteOn(48, 0.7, 0); noteOff(48, 8_820) }
        val c3 = peakNear(r.mono, PianoMath.midiToFreq(48.0), from = 1.5).second - peakNear(held.mono, PianoMath.midiToFreq(48.0), from = 1.5).second
        val c5 = peakNear(r.mono, PianoMath.midiToFreq(73.0), from = 1.5, lo = 0.99, hi = 1.01).second
        val c5Ringing = peakNear(pianoRender(3.0, Piano(sampleRate = sr, room = Param(0f), pedal = Param(1f))) { noteOn(48, 0.7, 0); noteOn(73, 0.7, 13_230); noteOff(73, 22_050) }.mono, PianoMath.midiToFreq(73.0), from = 1.5, lo = 0.99, hi = 1.01).second
        println("PIANO sostenuto: C3 at 1.5 s +${"%.1f".format(c3)} dB over released; C#5 pressed later ${"%.1f".format(c5 - c5Ringing)} dB against the sustain pedal")
        assertTrue(c3 > 20.0 && c5 < c5Ringing - 20.0)
    }

    /** A MIDI file to a WAV in one call, pedals and all. */
    @Test
    fun midiFileRendersToWav() {
        val clip = MidiClip(tracks = listOf(MidiTrackData(notes = listOf(
            MidiNote(pitch = 60, velocity = 90, startBeats = 0.0, durationBeats = 1.0),
            MidiNote(pitch = 64, velocity = 70, startBeats = 1.0, durationBeats = 1.0),
            MidiNote(pitch = 67, velocity = 70, startBeats = 2.0, durationBeats = 2.0),
        ))))
        val mid = File.createTempFile("piano", ".mid"); val wav = File.createTempFile("piano", ".wav")
        try {
            clip.writeTo(mid)
            val r = pianoRenderMidi(mid, wav, tailSeconds = 1.0)
            assertEquals(3.0, r.seconds, 0.05)
            assertTrue(wav.length() > 44 + 4 * sr * 2)
        } finally { mid.delete(); wav.delete() }
    }

    /** Every docs scene renders finite and audible; with PIANO_TEST_DUMP=1 they are written for listening. */
    @Test
    fun docsScenesRender() {
        for (name in listOf("piano-prepared", "piano-sliding-bolt", "piano-rattle", "piano-mic-pair", "piano-una-corda", "piano-through-piano", "piano-shared-board", "piano-through-prepared")) {
            val r = PianoDocs.scenes.getValue(name)()
            println("PIANO scene $name: ${"%.1f".format(r.seconds)} s, peak ${"%.2f".format(r.peak)}, rms ${"%.1f".format(rms(r.mono, 0.0, r.seconds))} dB")
            assertTrue(r.left.all { it.isFinite() } && r.peak > 0.01f, name)
            if (dump) r.writeWav(File(out, "scene_$name.wav"), 0.89f)
        }
    }

    /** Listening copies of every feature. */
    @Test
    fun preparedWavs() {
        if (!dump) return
        val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
        fun r(p: Piano, name: String) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }.writeWav(File(out, "$name.wav"), 0.89f)
        r(Piano(sampleRate = sr, preparations = listOf(PianoPreparation.bolt(60..84, at = 0.3, grams = 4.0), PianoPreparation.rubber(36..50, at = 0.12, amount = 25.0))), "prepared")
        r(Piano(sampleRate = sr, preparations = listOf(PianoPreparation.rattle(21..108, at = 0.2, level = 1.0))), "rattle")
        r(Piano(sampleRate = sr, dry = Param(1f), room = Param(0.1f), mics = listOf(PianoMic(Param(0.08f), Param(-0.8f)), PianoMic(Param(0.35f), Param(0.8f)))), "mic_pair")
        r(Piano(sampleRate = sr, unaCorda = Param(1f)), "una_corda")
        r(Piano(sampleRate = sr, preparations = listOf(PianoPreparation(21..108, position = Param(0.3f).modulate(SineOsc(freq = Param(0.25f)) * 0.2f), mass = Param(3f)))), "sliding_bolt")
    }
}
