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
        val before = sim.screenRevision
        sim.start(); p.rms(0.5)
        assertTrue(sim.screenRevision > before, "a running sequencer redraws the screen")
        assertEquals(3 * FM1Sim.SCREEN, javax.imageio.ImageIO.read(sim.screenPng(3).inputStream()).width)
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

    /** Power of [x] at [hz] (sampled at [sr]), by Goertzel. */
    private fun power(x: FloatArray, hz: Double, sr: Double): Double {
        val w = 2 * Math.PI * hz / sr; val c = 2 * kotlin.math.cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    private fun sine(hz: Double, seconds: Double, sr: Int) =
        FloatArray((seconds * sr).toInt()) { (0.7 * kotlin.math.sin(2 * Math.PI * hz * it / sr)).toFloat() }

    @Test
    fun theFactorySampleBankParsesAndRebuildsByteForByte() = FM1Sim.create().use { sim ->
        val bank = sim.sampleBank()
        val pads = bank.describe()
        assertEquals(8, pads.size)
        assertTrue(pads.all { !it.empty && it.name.isNotEmpty() }, "the factory fills every pad: $pads")
        assertEquals(listOf(36, 38, 37, 41, 47, 48, 42, 46), pads.map { it.note })
        val again = FM1SampleBank.parse(bank.bytes())
        assertTrue(again.bytes().contentEquals(bank.bytes()), "parse and bytes are inverses")
    }

    @Test
    fun fieldAudioOnAPadPlaysOnASamplerTrackAtItsOwnPitch() = FM1Sim.create().use { sim ->
        sim.preset(0)
        sim.source(0, FM1Sim.SourceType.SAMPLER)
        sim.sample(0, sine(440.0, 1.0, 48_000), 48_000.0, "SINE440")
        val p = Pull(sim, 44_100)
        p.take(0.2)
        assertEquals(FM1Sim.SourceType.SAMPLER, sim.tracks()[0].source)
        val pads = sim.samples()
        assertEquals("SINE440", pads[0].name)
        assertEquals(32_000, pads[0].frames, "one second at 32 kHz")
        assertEquals(0.0, p.rms(0.2), 1e-6, "silent before the note")
        sim.note(0, 36, seconds = 0.6, velocity = 127)
        val x = p.take(0.5)
        val at = power(x, 440.0, 44_100.0)
        assertTrue(at > 20 * power(x, 466.16, 44_100.0) && at > 20 * power(x, 415.3, 44_100.0),
            "bd plays the 440 Hz sine at 440 Hz, not a semitone off")
    }

    @Test
    fun replacingOnePadKeepsTheOthersAndClearingSilencesIt() = FM1Sim.create().use { sim ->
        val before = sim.sampleBank()
        sim.sample(2, sine(220.0, 0.25, 32_000), 32_000.0, "rim tone")
        val after = sim.sampleBank()
        for (i in listOf(0, 1, 3, 4, 5, 6, 7)) {
            assertEquals(before.pads[i].name, after.pads[i].name)
            assertTrue(before.pads[i].data.contentEquals(after.pads[i].data), "pad $i is untouched")
        }
        assertEquals("rim tone", after.pads[2].name)
        sim.clearSample(2)
        assertTrue(sim.samples()[2].empty)
        sim.preset(0); sim.source(0, FM1Sim.SourceType.SAMPLER)
        val p = Pull(sim, 44_100); p.take(0.2)
        sim.note(0, 37, seconds = 0.3, velocity = 127)
        assertEquals(0.0, p.rms(0.3), 1e-6, "an empty pad is silent")
        sim.factorySamples()
        assertTrue(sim.sampleBank().bytes().contentEquals(before.bytes()), "and the factory bank comes back")
    }

    @Test
    fun aBankTooLongForFlashIsRefusedAndLeavesThePadsAlone() = FM1Sim.create().use { sim ->
        val before = sim.sampleBank().bytes()
        val twelve = sine(100.0, 12.0, 32_000)
        sim.sample(0, twelve, 32_000.0, "A")
        val failure = runCatching { sim.sample(1, twelve, 32_000.0, "B") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && "bank holds" in failure.message!!, "24 s does not fit: $failure")
        assertEquals("A", sim.samples()[0].name, "pad 0 is still the first 12 s")
        assertTrue(!sim.sampleBank().bytes().contentEquals(before))
    }

    @Test
    fun resamplingTo32kHzKeepsLengthAndRemovesWhatWouldAlias() {
        val pcm = FM1SampleBank.toPcm(sine(1000.0, 0.5, 48_000), 48_000.0)
        assertEquals(16_000, pcm.size)
        val x = FloatArray(pcm.size) { pcm[it] / 32768f }
        assertTrue(power(x, 1000.0, 32_000.0) > 1e3 * power(x, 1100.0, 32_000.0), "1 kHz stays 1 kHz")
        // 20 kHz at 48 kHz would fold to 12 kHz at 32 kHz without the low-pass.
        val hi = FM1SampleBank.toPcm(sine(20_000.0, 0.5, 48_000), 48_000.0)
        val rms = sqrt(hi.sumOf { (it / 32768.0) * (it / 32768.0) } / hi.size)
        assertTrue(rms < 0.01, "20 kHz is filtered out, not folded down (rms $rms)")
        val nan = FM1SampleBank.toPcm(floatArrayOf(Float.NaN, 2f, -2f), 32_000.0)
        assertEquals(listOf<Short>(0, 32767, -32767), nan.toList())
    }

    @Test
    fun aDrumTrackTurnedSamplerPlaysItsSequenceOnTheNewPads() {
        // Deterministic renders, so the difference between them is only what the sampler plays.
        fun render(kit: Boolean): FloatArray = FM1Sim.create().use { sim ->
            sim.preset(1)
            for (pad in 0 until 8) sim.clearSample(pad)
            if (kit) sim.sample(0, sine(60.0, 0.3, 32_000), 32_000.0, "LOW")
            sim.source(1, FM1Sim.SourceType.SAMPLER)
            val p = Pull(sim, 44_100); p.take(0.2)
            sim.start()
            p.take(2.0)
        }
        val without = render(false)
        val with = render(true)
        val diff = FloatArray(with.size) { with[it] - without[it] }
        val rms = sqrt(diff.sumOf { it.toDouble() * it } / diff.size)
        assertTrue(rms > 0.005, "ACID ALLEY's kick steps play pad 0 (rms of the difference $rms)")
        assertTrue(power(diff, 60.0, 44_100.0) > 20 * power(diff, 90.0, 44_100.0), "and what they play is the 60 Hz pad")
    }

    @Test
    fun aModuleThatIsNotFM1FirmwareIsRefusedByName() {
        val empty = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0) // a valid, empty WebAssembly module
        val lacks = runCatching { FM1Sim.create(empty) }.exceptionOrNull()
        assertTrue(lacks is IllegalArgumentException && "lacks sim_block_frames" in lacks.message!!, "$lacks")
        val junk = runCatching { FM1Sim.create(ByteArray(64) { 7 }) }.exceptionOrNull()
        assertTrue(junk is IllegalArgumentException && "not a loadable" in junk.message!!, "$junk")
    }

    @Test
    fun loadingFirmwareMidSequenceKeepsTheProjectPadsVoicesAndTransport() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(1)
        sim.sample(0, sine(200.0, 0.2, 32_000), 32_000.0, "KEPT")
        val voices = sim.voices(1).toMutableList()
        voices[0] = voices[0].copy().apply { name = "KEPTVOICE" }
        sim.setVoices(1, voices)
        sim.midiOut(1, FM1Sim.MidiOut.ALSO)
        sim.level(0.5)
        sim.start()
        p.rms(1.0)
        assertTrue(sim.isPlaying)
        sim.drainMidi()
        val name = sim.projectName
        val before = sim.blocksRendered
        assertTrue(sim.builtInFirmware)

        sim.loadFirmware(FM1Sim.builtInFirmware())

        assertEquals(before, sim.blocksRendered, "the block clock carries on rather than restarting")
        assertTrue(p.rms(1.0) > 0.005, "the new module plays at once, from the same node")
        assertTrue(sim.isPlaying, "and the sequence is still running")
        assertEquals(name, sim.projectName)
        assertEquals("KEPT", sim.samples()[0].name)
        assertEquals("KEPTVOICE", sim.voices(1)[0].name)
        assertTrue(sim.drainMidi().isNotEmpty(), "track 1 still sends its notes as MIDI")
        assertEquals(64, sim.firmwareSha256.length)

        val refused = runCatching { sim.loadFirmware(ByteArray(16)) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException, "$refused")
        assertTrue(p.rms(0.5) > 0.005 && sim.isPlaying, "a refused module leaves the old one playing")
    }

    @Test
    fun loadingFirmwareWithoutStateBootsTheFactoryStorage() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.sample(0, sine(200.0, 0.2, 32_000), 32_000.0, "GONE")
        sim.loadFirmware(FM1Sim.builtInFirmware(), keepState = false)
        p.take(0.1)
        assertTrue(sim.samples()[0].name != "GONE", "factory pads come back: ${sim.samples()[0]}")
    }

    @Test
    fun aSwapRightAfterStartKeepsTheSequencePlaying() = FM1Sim.create().use { sim ->
        val p = Pull(sim)
        sim.preset(1)
        p.take(0.2)
        sim.start()
        sim.loadFirmware(FM1Sim.builtInFirmware()) // no block rendered since start()
        p.take(0.5)
        assertTrue(sim.isPlaying, "the Start delivered just before the swap still counts")
    }
}
