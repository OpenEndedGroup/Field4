package afield.audio.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins what Field drives in the FM-1 simulator: MIDI in and out, patterns, voices, projects, the screen. */
class FM1SimTest {

    private class Pull(val sim: FM1Sim, sr: Int = 48_000) {
        val ctx = SynthContext(sr.toFloat(), 128)
        val node = FM1SimNode(sim)
        val l = FloatArray(128)
        val r = FloatArray(128)
        var block = 0L

        /** Render [seconds] and return the RMS of the left channel over them. */
        fun rms(seconds: Double): Double {
            var ss = 0.0
            val n = (seconds * ctx.sampleRate / 128).toInt()
            repeat(n) {
                ctx.cycleId = ++block
                node.render(l, r, 128, ctx)
                for (x in l) ss += x * x
            }
            return sqrt(ss / (n * 128))
        }

        /** First-difference RMS over RMS: how much of the sound is high frequency. */
        fun brightness(seconds: Double): Double {
            val x = take(seconds)
            var ss = 0.0; var dd = 0.0
            for (i in 1 until x.size) { ss += x[i] * x[i]; dd += (x[i] - x[i - 1]).let { it * it } }
            return sqrt(dd / ss)
        }

        fun take(seconds: Double): FloatArray {
            val n = (seconds * ctx.sampleRate / 128).toInt()
            val out = FloatArray(n * 128)
            repeat(n) {
                ctx.cycleId = ++block
                node.render(l, r, 128, ctx)
                l.copyInto(out, it * 128)
            }
            return out
        }
    }

    @Test
    fun aNoteOnATracksChannelSoundsAndStopsWhenReleased() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(0)
        p.rms(0.2)
        assertEquals(0.0, p.rms(0.2), 1e-6, "a stopped project is silent")
        sim.note(0, 60, seconds = 0.3, velocity = 110)
        val on = p.rms(0.3)
        assertTrue(on > 0.005, "track 0's DX7 sounds a note sent on its channel (rms $on)")
        p.rms(1.5)
        assertTrue(p.rms(0.2) < on / 10, "and it is released")
    }

    @Test
    fun aJavaSoundMessageFromAKeyboardPlaysTheTrackOnItsChannel() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(0); p.rms(0.2)
        sim.receive(javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 1, 64, 100))
        assertTrue(p.rms(0.3) > 0.005, "track 1 listens on channel 1")
    }

    @Test
    fun startAndStopAreTransportNotAToggle() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(1)
        p.rms(0.2)
        sim.start(); p.rms(0.3)
        assertTrue(sim.isPlaying)
        sim.start(); p.rms(0.3)
        assertTrue(sim.isPlaying, "a second start restarts rather than stopping")
        sim.stop(); p.rms(0.3)
        assertTrue(!sim.isPlaying)
    }

    @Test
    fun sixteenFactoryProjectsLoadByName() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        val names = (0 until FM1Sim.FACTORY_PROJECTS).map { sim.factoryName(it) }
        assertEquals("NEON KEYS", names[0])
        assertEquals(FM1Sim.FACTORY_PROJECTS, names.toSet().size, "distinct names: $names")
        sim.preset(15); p.rms(0.1)
        assertEquals(names[15], sim.projectName)
    }

    @Test
    fun aProjectRoundTripsThroughItsBytes() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(6); p.rms(0.1)
        val bytes = sim.exportProject()
        assertEquals(FM1Sim.PROJECT_BYTES, bytes.size)
        sim.preset(0); p.rms(0.1)
        assertEquals("NEON KEYS", sim.projectName)
        sim.importProject(bytes)
        assertEquals(sim.factoryName(6), sim.projectName)
        assertTrue(sim.factoryProject(6).copyOf(4064).contentEquals(bytes.copyOf(4064)))
    }

    @Test
    fun tracksReportSourcesTempoAndChannels() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(1); p.rms(0.1)
        val t = sim.tracks()
        assertEquals(FM1Sim.SourceType.BASS_303, t[0].source)
        assertEquals(FM1Sim.SourceType.DRUM_808, t[1].source)
        assertEquals(130, sim.bpm)
        assertEquals(130 / 240.0, sim.cps, 1e-12)
        assertEquals(listOf(0, 1, 2), t.take(3).map { it.channel })
        assertTrue(t[1].steps.isNotEmpty())
    }

    @Test
    fun dx7VoicesRoundTripThroughVmemSysexAndTheLibrary() = FM1Sim.create().use { sim ->
        val bank = sim.voices(0)
        assertEquals(32, bank.size)
        assertEquals("PIANO 1", bank[0].name)
        for (v in bank) assertEquals(v, Dx7Voice.fromVmem(v.toVmem()))
        val sysex = bank[0].toSysex(2)
        assertEquals(163, sysex.size)
        assertEquals(0, (6 until 162).sumOf { sysex[it].toInt() } and 0x7f, "checksum")
        val syx = Dx7Voice.bankSysex(bank)
        assertEquals(bank, Dx7Voice.bank(syx))

        val edited = bank.map { it.copy().apply { feedback = 7; op(1).coarse = 3 } }
        sim.setVoices(1, edited)
        assertEquals(edited, sim.voices(1))
    }

    @Test
    fun lerpIsEndpointsAtZeroAndOneAndExtrapolatesToTheRangeEnds() {
        val a = Dx7Voice().apply { op(1).level = 20; feedback = 1 }
        val b = Dx7Voice().apply { op(1).level = 80; feedback = 5 }
        assertEquals(a, a.lerp(b, 0.0))
        assertEquals(b, a.lerp(b, 1.0))
        assertEquals(50, a.lerp(b, 0.5).op(1).level)
        assertEquals(99, a.lerp(b, 11.0).op(1).level, "past 1 goes on to the end of the range")
        assertEquals(0, a.lerp(b, -11.0).op(1).level)
        assertEquals(99, a.lerp(b, Double.POSITIVE_INFINITY).op(1).level)
    }

    @Test
    fun aVoiceSentToATrackChangesItsSound() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(0); p.rms(0.2)
        sim.note(0, 60, seconds = 0.4); val before = p.take(0.4); p.rms(1.0)
        val loud = sim.voices(0)[0].copy().apply { for (n in 1..6) op(n).level = 99; algorithm = 32 }
        sim.voice(0, loud); p.rms(0.1)
        sim.note(0, 60, seconds = 0.4); val after = p.take(0.4)
        val diff = before.indices.sumOf { abs(before[it] - after[it]).toDouble() }
        assertTrue(diff > 1.0, "the new voice sounds different (diff $diff)")
    }

    @Test
    fun aScheduledPatternIsDeterministicAndOnItsBlocks() {
        fun render(): FloatArray = FM1Sim.create().use { sim ->
            val p = Pull(sim)
            sim.preset(0); p.rms(0.2)
            val n = sim.schedule(pat("c4 e4 g4 c5"), track = 0, cycles = 2, cps = 1.0)
            assertEquals(8, n)
            p.take(2.0)
        }
        val a = render()
        assertTrue(a.any { abs(it) > 0.01f })
        assertTrue(a.contentEquals(render()))
    }

    @Test
    fun patternsReadDrumNamesNotesAndGainAndSkipNaN() {
        val q = fm1NotesForArc(pat("bd sd ~ hh"), PatArc(PatQ.ZERO, PatQ.ONE), cps = 1.0)
        assertEquals(listOf(36, 38, 42), q.notes.map { it.note })
        val starts = q.notes.map { it.startBlock }
        assertEquals(listOf(0L, 86L, 258L), starts, "quarter-cycle onsets on the 344.5-block cycle")
        val loud = fm1NotesForArc(pat("c5") with gain(4), PatArc(PatQ.ZERO, PatQ.ONE), cps = 1.0)
        assertEquals(127, loud.notes.single().velocity, "gain past MIDI's top is its top")
        val nan = fm1NotesForArc(pat("c5") with paramOf("gain", pure("NaN")), PatArc(PatQ.ZERO, PatQ.ONE), cps = 1.0)
        assertEquals(1, nan.skipped)
        val rest = fm1NotesForArc(pat("c5 e5") with gain("1 0"), PatArc(PatQ.ZERO, PatQ.ONE), cps = 1.0)
        assertEquals(listOf(60), rest.notes.map { it.note }, "a gain of 0 is a rest")
    }

    @Test
    fun aTrackSentToMidiOutComesBackAsAClip() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(1); p.rms(0.1)
        sim.midiOut(1, FM1Sim.MidiOut.ONLY)
        sim.start(); p.rms(4.0)
        val events = sim.drainMidi()
        assertTrue(events.count { (it.bytes[0] and 0xF0) == 0x90 } > 4, "note-ons: $events")
        val clip = events.toMidiClip(sim.bpm.toDouble())
        assertEquals(1, clip.tracks.size, "one channel: the 808's")
        assertTrue(clip.tracks[0].notes.all { it.pitch in FM1_DRUM_NOTES.values })
    }

    @Test
    fun aSignalTurnsAControllerAndNaNHoldsIt() = FM1Sim.create().use { sim ->
        fun cutoff(value: Float): Double {
            val p = Pull(sim)
            sim.modulate(0, FM1Sim.CC.CUTOFF, Const(value))
            sim.start(); p.rms(0.5)
            return p.brightness(2.0).also { sim.stop(); p.rms(0.3) }
        }
        sim.preset(1)
        // The 303 alone: the 808 and the DX7 send MIDI instead of sounding.
        sim.midiOut(1, FM1Sim.MidiOut.ONLY); sim.midiOut(2, FM1Sim.MidiOut.ONLY)
        val dark = cutoff(0f)
        val bright = cutoff(1f)
        assertTrue(bright > dark * 1.2, "an open filter is brighter (dark $dark, bright $bright)")
        val held = cutoff(Float.NaN)
        assertTrue(held.isFinite())
    }

    @Test
    fun theScreenAndKnobsShowTheProject() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(0); p.rms(0.3)
        val px = sim.screen()
        assertEquals(FM1Sim.SCREEN * FM1Sim.SCREEN, px.size)
        assertTrue(px.count { (it and 0xffffff) != 0 } > 2000)
        assertTrue(sim.screenPng().size > 1000)
        assertTrue(sim.knobs().any { it.first == "LEVEL" }, "${sim.knobs()}")
    }

    @Test
    fun at44100TheInterpolatorPassesTheBlocksThrough() = FM1Sim.create().use { a ->
        FM1Sim.create().use { b ->
            for (s in listOf(a, b)) { s.preset(0); s.start() }
            val pa = Pull(a, 44_100).take(1.0)
            // The same sim pulled in odd-sized chunks gives the same samples.
            val l = FloatArray(1000); val r = FloatArray(1000)
            val out = FloatArray(pa.size)
            var at = 0
            while (at < out.size) {
                val n = minOf(1000, out.size - at)
                b.render(l, r, n, 44_100f); l.copyInto(out, at, 0, n); at += n
            }
            assertTrue(pa.contentEquals(out))
            assertTrue(pa.any { it != 0f })
        }
    }
}
