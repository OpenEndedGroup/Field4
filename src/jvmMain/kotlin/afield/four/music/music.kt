/*
 Live-coding entrypoints for afield.four.music.

 This file is the terse surface that boxes import.  The examples below
 assume the default Field preamble, which now includes:

     import afield.four.music.*
     import afield.four.synth.*   // for Signal, Gate, Param, audio { }, etc.

 Core ideas:

 - [BeatClock] is the tempo source; create one per piece.
 - [NoteScheduler] unrolls a Sequence<Note> ahead of the playhead, driving
   a Gate and two Params (freq, velocity).
 - Placing `sched +` before your audio signal in the graph ensures the
   scheduler fires events before the oscillator renders each cycle.
 - Pattern swaps are applied at measure boundaries by default (no glitches).
 - [cycle], [euclid], and scale helpers live in Patterns.kt.

 ---------------------------------------------------------------------------
 Quick start
 ---------------------------------------------------------------------------

 Create a clock and a scheduler:

     val clk = clock(120.0)           // 120 BPM, 4/4 time
     val sched = clk.scheduler {
         cycle(listOf(60.n, 62.n, 64.n, 65.n, 67.n))
     }

 Wire the scheduler into a synth graph.  `sched` renders first (setting
 gate / freq), then the oscillator reads the updated values:

     audio {
         sched + SineOsc(sched.freq) *
             AR(gate = sched.gate, attack = 0.005.p, release = 0.25.p) * 0.3f
     }

 ---------------------------------------------------------------------------
 Notes and rests
 ---------------------------------------------------------------------------

 MIDI number to Note:

     60.n          // middle C, 1-beat duration, velocity 0.8
     60.n(0.5)     // middle C, half-beat (eighth note in 4/4)
     60.n(0.25)    // sixteenth note

 Named note constructor:

     note("C", 4)                     // C4 = MIDI 60
     note("F#", 3, duration = 0.25)   // F#3, sixteenth note
     note("Eb", 5, velocity = 0.5f)   // Eb5, softer

 Rests:

     rest          // 1-beat rest (property; creates a new Note each time)
     rest(0.5)     // half-beat rest

 ---------------------------------------------------------------------------
 Patterns and sequences
 ---------------------------------------------------------------------------

 Cycle a list forever:

     cycle(listOf(60.n, 64.n, 67.n))          // C-E-G arpeggio
     cycle(60.n, 64.n, 67.n)                  // vararg shorthand
     cycle(listOf(60.n(0.5), rest(0.5)))       // note-rest-note-rest…

 Euclidean rhythm (Bjorklund algorithm) – 3 pulses in 8 slots:

     val hits = euclid(8, 3)        // [T,F,F,T,F,F,T,F]  (classic tresillo)
     val pat  = hits.toNoteSequence(60.n(0.125), offDuration = 0.125)
     sched.setPattern(pat)

 Rotate a Euclidean pattern by 2 steps:

     euclid(8, 3, rotate = 2)       // [F,T,F,F,T,F,F,T]  → shifted tresillo

 More classic Euclidean patterns:

     euclid(8, 5)     // cinquillo
     euclid(12, 7)    // 12/8 African feel
     euclid(16, 9)    // West African standard bell

 Rotate a list:

     listOf(60.n, 62.n, 64.n).rotated(1)    // [62.n, 64.n, 60.n]

 Scale helpers:

     val root = 60   // C4
     root.scale(Scales.minor)           // [60,62,63,65,67,68,70]
     root.scale(Scales.pentatonicMajor) // [60,62,64,67,69]

     // Get a note from a scale by degree
     Scales.major.degree(0, root = 60)  // 60
     Scales.major.degree(7, root = 60)  // 72 (octave up)
     Scales.major.degree(-1, root = 60) // 59 (B below root)

 Build a sequence from a scale:

     val scale = root.scale(Scales.minor)
     cycle(scale.map { it.n(0.5) })

 ---------------------------------------------------------------------------
 Updating patterns live
 ---------------------------------------------------------------------------

 At the next measure boundary (default – glitch-free):

     sched.setPattern(cycle(listOf(60.n, 63.n, 67.n, 70.n)))

 Immediately (applies on the next audio cycle):

     sched.setPattern(cycle(listOf(60.n, 62.n, 64.n)), immediate = true)

 Convenience – pass a list directly:

     sched.setPattern(listOf(60.n, 64.n, 67.n))

 Change tempo without touching the pattern:

     clk.bpm = 140.0

 Read back the current beat position:

     println(clk.currentBeat)         // set by the audio thread each cycle

 ---------------------------------------------------------------------------
 Generator-based patterns
 ---------------------------------------------------------------------------

 Use Field's Generator class for stateful, adaptive melodies:

     import afield.generator

     val gen = generator<Unit, Note> {
         val scale = listOf(60, 62, 64, 67, 69)
         var step = 0
         while (true) {
             yield(Note(scale[step % scale.size], duration = 0.25))
             step++
         }
     }
     sched.setPattern(gen.asNoteSequence())

 ---------------------------------------------------------------------------
 Polyphony with multiple schedulers
 ---------------------------------------------------------------------------

 Two independent voices sharing the same clock:

     val sched1 = clk.scheduler { cycle(listOf(60.n(1.0), 64.n(1.0))) }
     val sched2 = clk.scheduler { cycle(listOf(rest(0.5), 67.n(0.5), rest(0.5))) }

     audio {
         val osc1 = SawOsc(sched1.freq) * AR(gate = sched1.gate, release = 0.3.p)
         val osc2 = SineOsc(sched2.freq) * AR(gate = sched2.gate, release = 0.2.p)
         sched1 + sched2 + (osc1 + osc2) * 0.2f
     }

 ---------------------------------------------------------------------------
 Scheduling precision
 ---------------------------------------------------------------------------

 Notes are triggered at *buffer* granularity: an event due anywhere within
 the current audio buffer fires at its start.  With the default buffer size
 (512 samples @ 48 kHz) this gives ≈ 10 ms jitter – imperceptible for most
 musical purposes.  Notes shorter than two buffers (≈ 20 ms) may occasionally
 have their gate missed; for very fast passages use shorter note durations
 with a generous release on the envelope.

 ---------------------------------------------------------------------------
 Lookahead and update latency
 ---------------------------------------------------------------------------

 The default [NoteScheduler.lookaheadBeats] is 2.  At 120 BPM that pre-fills
 one second of notes.  Larger values survive scheduling hiccups but increase
 the latency between `setPattern()` and when the new pattern audibly starts
 (the old pattern plays out to the next measure before the swap takes effect).
 Reduce lookahead if you want faster response; increase it on a loaded system.
*/
package afield.four.music

import afield.four.synth.Gate
import afield.four.synth.Param

// ── Clock factory ────────────────────────────────────────────────

/**
 * Create a [BeatClock] with the given tempo and meter.
 *
 * ```kotlin
 * val clk = clock(120.0)          // 120 BPM, 4/4
 * val clk = clock(100.0, beatsPerMeasure = 3)  // 100 BPM waltz
 * ```
 */
fun clock(bpm: Double = 120.0, beatsPerMeasure: Int = 4): BeatClock =
    BeatClock(bpm, beatsPerMeasure)

// ── Scheduler factories ──────────────────────────────────────────

/**
 * Create a [NoteScheduler] attached to [this] clock, using the note sequence
 * returned by [block] as the initial pattern.
 *
 * ```kotlin
 * val clk = clock(120.0)
 * val sched = clk.scheduler {
 *     cycle(listOf(60.n, 64.n, 67.n))
 * }
 * ```
 */
fun BeatClock.scheduler(
    lookaheadBeats: Double = 2.0,
    gate: Gate = Gate(),
    freq: Param = Param(440f),
    velocity: Param = Param(0.8f),
    block: () -> Sequence<Note>
): NoteScheduler = NoteScheduler(this, lookaheadBeats, gate, freq, velocity, block())

/**
 * Create a [NoteScheduler] attached to [this] clock with no initial pattern.
 * Call [NoteScheduler.setPattern] before the scheduler is heard.
 *
 * ```kotlin
 * val sched = clk.scheduler()
 * sched.setPattern(cycle(listOf(60.n, 64.n)))
 * ```
 */
fun BeatClock.scheduler(
    lookaheadBeats: Double = 2.0,
    gate: Gate = Gate(),
    freq: Param = Param(440f),
    velocity: Param = Param(0.8f)
): NoteScheduler = NoteScheduler(this, lookaheadBeats, gate, freq, velocity)

/**
 * Standalone scheduler factory (without a clock extension receiver).
 *
 * ```kotlin
 * val clk   = clock(120.0)
 * val sched = scheduler(clk) { cycle(listOf(60.n, 64.n, 67.n)) }
 * ```
 */
fun scheduler(
    clock: BeatClock,
    lookaheadBeats: Double = 2.0,
    gate: Gate = Gate(),
    freq: Param = Param(440f),
    velocity: Param = Param(0.8f),
    block: () -> Sequence<Note>
): NoteScheduler = NoteScheduler(clock, lookaheadBeats, gate, freq, velocity, block())

// ── Note factory ─────────────────────────────────────────────────

/**
 * Create a [Note] from a letter name and octave.
 *
 * ```kotlin
 * note("C", 4)                         // MIDI 60, 1 beat
 * note("F#", 3, duration = 0.25)       // sixteenth note
 * note("Bb", 4, velocity = 0.5f)       // softer
 * ```
 */
fun note(
    name: String,
    octave: Int = 4,
    velocity: Float = 0.8f,
    duration: Double = 1.0
): Note = Note.fromName(name, octave, velocity, duration)
