# FM-1 simulator

The [M-VAVE FM-1](https://open.m-vave.com/fm1-workbench) is a pocket groovebox: eight tracks of 64 steps,
with DX7-style FM, a 303 bass line, 808 drums, a plucked string, a chip-tune PSG and a sampler. Its
firmware is open source, and the same source builds a simulator that runs in a web page. Field runs that
simulator in its audio engine, so a box can play the FM-1's sixteen factory projects, play any of its
tracks by pitch, hand a track a Field pattern, turn its controls with a signal, give it DX7 voices built
in Field, and read its sequence back out as MIDI.

There is nothing to install and no hardware involved. The simulator comes with Field as a WebAssembly
module, built from the [open firmware](https://github.com/m-vave/fm1_open).

## A box that plays it

This box loads factory project 1, starts the sequencer, and returns the simulator as its last
expression, so launching the box puts it in the mix:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared

fm1.preset(1) // a factory project, 0 to 15: change it and re-run
fm1.start() // start from the first step, playing or not
fm1.level(0.8)

fm1.node
```

`FM1Sim.shared` is one simulator for the whole of Field. Re-running the box loads the new project into
it, so you can change the slot and hear the next project. Stopping the box takes it out of the mix;
`fm1.stop()` stops the sequencer itself.

Factory project 0 sounds like this, six seconds from the moment PLAY is pressed:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/project-0.wav" audio-spec="project-0" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-project-0"}
// FM1SoundDocs.renderWav("project-0", …): factory project 0, PLAY tapped, six seconds offline
```

And factory project 1, the one the box above plays:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/project-1.wav" audio-spec="project-1" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-project-1"}
// FM1SoundDocs.renderWav("project-1", …): factory project 1, PLAY tapped, six seconds offline
```

`fm1.factoryName(slot)` names each of the sixteen, and `fm1.tracks()` says what each track of the
loaded project holds: its sound source (`DX7`, `BASS_303`, `DRUM_808`, `PSG`, `PLUCK` or `SAMPLER`), its
volume, the steps that hold notes, the tempo and the MIDI channel it listens on.

## Playing notes on any track

Every track listens on a MIDI channel, so a box can play any of them by pitch. In project 1, track 0 is
the 303, track 1 the 808 and track 2 a DX7 voice; this plays a chord on the DX7 and a kick and snare on
the 808, a quarter of a second apart:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
fm1.chord(2, [60, 64, 67], seconds = 1.0)
fm1.note(1, 36, afterSeconds = 0.25) // the 808's kick
fm1.note(1, 38, afterSeconds = 0.5) // and snare
```

`afterSeconds` puts each note on the simulator's own clock, so the box returns at once and the notes land
on time. Timing is to the simulator's block of 128 samples, about 3 ms.

| call | what it does |
| --- | --- |
| `note(track, note, seconds = 0.25, velocity = 100, afterSeconds = 0.0)` | play a MIDI note (60 is middle C) for a while; a fractional note is rounded |
| `chord(track, notes, seconds = 0.5)` | several notes struck together |
| `noteOn(track, note, velocity)`, `noteOff(track, note)` | hold and release a note yourself |
| `cc(track, controller, value)` | set a controller, 0 to 127; `FM1Sim.CC` names the ones the voices answer to |
| `bend(track, amount)` | pitch bend, -1 to 1 of the bend range (two semitones on a DX7 track) |
| `program(track, n)` | switch a DX7 track to voice `n` of the 128 in the voice library |
| `start()`, `stop()`, `resume()` | the sequencer's transport, as MIDI Start, Stop and Continue |
| `midi(0x90, 60, 100)` | any raw MIDI message |

On a drum track the note picks the drum: 36 is the kick, 38 the snare, 42 the closed hat and 46 the open
one, 39 the clap, 37 the rim, 49 the crash, 56 the cowbell, and 41, 45 and 48 the toms.

The controllers each voice answers to are `CC.VOLUME`, `PAN`, `EXPRESSION`, `REVERB` and `MOD_WHEEL` on
every track; `CUTOFF`, `RESONANCE`, `DECAY`, `ENV_MOD` and `SLIDE` on the 303; `CUTOFF`, `RELEASE` and
`DAMPING` on the pluck and `CUTOFF` and `RELEASE` on the PSG. A DX7 voice is shaped by its patch, and
answers `MOD_WHEEL`, `BREATH`, `FOOT` and `SUSTAIN` through the patch's own modulation settings.

### From a keyboard

`fm1.listen(input)` sends everything a MIDI device plays to the simulator, which routes it by channel
to the tracks listening there:

```diffstatic {data-lang=kotlin}
val keys = MidiDevices.openInput(MidiDevices.inputs().first())
FM1Sim.shared.listen(keys)
```

## Playing a pattern on a track

`fm1.play(pattern, track)` keeps playing a Field pattern on one track, in time with the project: unless
you give a `cps`, one cycle lasts one bar of the project's tempo. This lays a line over project 1's DX7
track while the project's own 303 and 808 carry on:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
fm1.preset(1)
fm1.start()
fm1.play(pat("<c5 ef5> ~ [g5 bf5] ~ c6 ~ g5 ~"), track = 2)

fm1.node
```

Re-running the box with a different pattern replaces the one on that track, and
`fm1.stopPatterns(track)` stops it. The atoms are read as notes (`c5` is middle C, as everywhere in
Field's patterns), the `note` parameter is a MIDI note, and the drum names `bd`, `sd`, `hh`, `oh`, `cp`,
`rim`, `cr`, `cb`, `lt`, `mt` and `ht` play the drum tracks: `fm1.play(pat("bd*2 [~ sd] hh*4"), track = 1)` plays on top of the 808's own
pattern. `gain` scales the velocity, and a gain of 0 is a rest.

Here are three cycles of it over the project, rendered offline:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/pattern.wav" audio-spec="pattern" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-pattern"}
// FM1SoundDocs.renderWav("pattern", …): project 1 started, three cycles of the pattern on track 2
```

### From the drawing layer

A region of the drawing layer becomes a pattern with `toPattern()` (see
[Drawing and notation](drawing_and_notation.md)), so a score drawn on the canvas plays a track. Here
x runs over eight beats and y over two octaves of MIDI notes, from 84 at the top to 60 at the bottom:

```diffstatic {data-lang=kotlin}
val score = notation.space("score", x = 0.0..8.0, y = 84.0..60.0)
FM1Sim.shared.play(score.toPattern(), track = 0, cps = FM1Sim.shared.cps / 2)
```

The drawn score is one cycle, so halving `cps` gives its eight beats two bars of the project's tempo. A
note drawn between two lines is rounded to the nearer one.

## Turning a control with a signal

`fm1.modulate(track, controller, signal)` lets any of Field's signals turn a controller: 0 is the bottom
of its travel and 1 the top. This sweeps the 303's filter in project 1 with a sine that takes four
seconds to go round:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
fm1.preset(1)
fm1.start()
fm1.modulate(0, FM1Sim.CC.CUTOFF, SineOsc(0.25.p) * 0.5 + 0.5)

fm1.node
```

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/cutoff-lfo.wav" audio-spec="cutoff-lfo" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-cutoff-lfo"}
// FM1SoundDocs.renderWav("cutoff-lfo", …): project 1 with a 0.25 Hz sine on the 303's cutoff
```

The signal is read once per audio block in the same pass that plays the simulator, so an LFO used here is
the same LFO wherever else it is heard, and an envelope follower or a `Param` from a slider works as
well. A NaN leaves the controller where it was; values past 0 and 1 hold it at the end of its travel,
since a MIDI controller has 128 steps. `modulateBend(track, signal)` does the same for the pitch bend,
and `stopModulating()` lets go of everything.

### The FM-1 inside a signal graph

`fm1.node` is a stereo source like any other, so it can go through Field's filters and effects.
`toMono()` gives one channel to work with; this keeps the right channel as it is and puts the left
through a low-pass filter:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
val dry = fm1.node.toMono()
StereoSignal(dry / OnePole(500.p), dry)
```

## DX7 voices

The DX7 tracks play voices from the FM-1's voice library: four banks of 32, which `program(track, n)`
picks from. `fm1.voices(bank)` reads a bank as `Dx7Voice`s, which a box can edit and send back to a track
with `fm1.voice(track, v)`:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
val piano = fm1.voices(0)[0] // "PIANO 1"
val bell = piano.copy()
bell.algorithm = 5
bell.feedback = 6
bell.op(2).coarse = 7 // operator 2 at seven times the note's frequency
bell.op(6).coarse = 11
bell.name = "BELLPIANO"

fm1.voice(0, piano.lerp(bell, 0.5)) // halfway between the two
```

`lerp(other, t)` blends two voices parameter by parameter: 0 is the first, 1 the second, and values
outside 0 to 1 carry on past them until each parameter reaches the end of its range. Switches such as
the algorithm change over at the halfway point. Each operator has `level`, `coarse`, `fine`, `detune`,
`velocity`, `ampMod`, `fixed` and its envelope's `rate(i)` and `egLevel(i)`; the voice has `algorithm`,
`feedback`, `transpose`, the LFO and its `name`. Each parameter has the DX7's own range, and a value set
past it is held at the end.

This clip plays the same chord eight times on project 0's first track while the voice moves from the
factory piano to that bell, an eighth of the way each time:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/voice-morph.wav" audio-spec="voice-morph" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-voice-morph"}
// FM1SoundDocs.renderWav("voice-morph", …): a C major chord eight times, piano.lerp(bell, k / 7)
```

A DX7 bank file (a 32-voice `.syx` dump) loads into a library bank with `fm1.loadDx7Bank(file, bank)`,
after which `program(track, bank * 32 + i)` plays its voices. `fm1.setVoices(bank, voices)` writes 32
voices of your own, and `Dx7Voice.bankSysex(voices)` makes a `.syx` file of them for a real DX7 or an
editor.

## The FM-1's sequence as MIDI

A track can send its notes out of the simulator as well as, or instead of, playing them:
`midiOut(track, FM1Sim.MidiOut.ALSO)` or `MidiOut.ONLY`. `drainMidi()` collects what was sent, and
`toMidiClip(bpm)` turns it into a clip that Field can draw, save as a MIDI file or play as a pattern with
`toPat()`. This renders project 1 offline for one pass through its 64 steps and draws the 303's and the
808's parts as a piano roll:

```diffstatic_runner {data-lang=kotlin screenshot="assets/fm1/acid_alley_roll.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" wait-output-channel="lines" w="900" timeout="240" embed=true name="fm1-roll" alt="a piano roll of four bars of ACID ALLEY, the 303's bass line and the 808's drums together: a steady row of low notes, a busier row above it, and a few high notes climbing at the end of each bar"}
val fm1 = FM1Sim.create()
fm1.preset(1)
fm1.midiOut(0, FM1Sim.MidiOut.ALSO)
fm1.midiOut(1, FM1Sim.MidiOut.ALSO)

val l = FloatArray(4800)
val r = FloatArray(4800)
repeat(5) { fm1.render(l, r, 4800, 48000f) }
fm1.start()
repeat(75) { fm1.render(l, r, 4800, 48000f) } // 7.5 seconds: four bars at 130 bpm

showMidiRoll(fm1.drainMidi().toMidiClip(fm1.bpm.toDouble()))
fm1.close()
```

With `MidiOut.ONLY` the FM-1's sequencer plays something else: drain its notes in a loop and hand them
to a Faust instrument or a MIDI device.

## The screen

`fm1.screenPng(scale)` is a picture of the FM-1's LCD as it is now, which `display` shows in the box's
output. This loads factory project 4 and shows its screen at three times its size:

```diffstatic_runner {data-lang=kotlin screenshot="assets/fm1/screen.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" wait-output-channel="screen" w="760" timeout="240" embed=true name="fm1-screen" alt="the FM-1's LCD at three times its size: CHIP PARADE at 142 BPM, stopped at step 01 of 64, its eight tracks listed with their sources (PSG YM, PSG AY, 808 KIT, MARIMBA and four muted PSG tracks) and step patterns, and LEVEL 64, SOURCE PSG, STEP 01 and BPM 142 along the bottom"}
val fm1 = FM1Sim.create()
fm1.preset(4)
val l = FloatArray(4800)
val r = FloatArray(4800)
repeat(5) { fm1.render(l, r, 4800, 48000f) }

display("screen", fm1.screenPng(scale = 3))
fm1.close()
```

`fm1.knobs()` has the labels and values the screen shows beside the encoders, as text.

## Saving projects

`fm1.saveProject(file)` writes the project the simulator has loaded, in the FM-1's own format, and
`fm1.loadProject(file)` reads one back, so an edit made on the panel or from code can be kept beside the
document. `exportProject()` and `importProject(bytes)` do the same with the bytes, and
`factoryProject(slot)` gives a factory project's bytes without loading it.

## Playing the panel from code

The panel itself is there too. Every call queues a control change that the simulator picks up at the
start of its next block, so it is safe to call from a box while the sound plays.

| call | what it does |
| --- | --- |
| `preset(slot)` | load a factory project, 0 to 15 (0 is "NEON KEYS") |
| `play()` | press PLAY/STOP, which toggles the sequencer |
| `whiteKey(n, blocks = 40, afterBlocks = 0)` | play white key `n` (1 to 16) for that many 128-frame blocks, optionally starting later; the white keys are also the step keys 01 to 16 |
| `tap(FM1Sim.Button.REC)` | press and release a panel button: `OCT_DOWN`, `OCT_UP`, `FX`, `SEL`, `ENV`, `LFO`, `EDIT`, `GLO`, `HOME`, `SAVE`, `ARP`, `SEQ`, `PLAY`, `REC` |
| `button(index, down)`, `key(index, down)` | hold or release a button or any of the 27 keys yourself |
| `encoder(index, delta)` | turn one of the seven encoders by some detents |
| `select(track)` | select a track, so the keys, encoders and screen work on it |
| `step(n)` | toggle step `n` of the selected track |
| `level(0.0..1.0)` | the output-level knob |
| `panic()`, `release()` | silence every voice; let go of everything held |
| `isPlaying` | whether the sequencer is running |

A button press has to last at least one of the firmware's 10 ms control scans to be seen, which is why
`tap` and `whiteKey` hold for a number of blocks rather than pressing and releasing at once. The white
keys play the selected track in the panel's octave; `note` plays any track at any pitch.

## Rendering it offline

`FM1Sim.create()` makes a simulator of its own, which you pull audio from directly and close when you
are done. Nothing here needs an audio device, so it works in a test or a docs build. This renders three
seconds of each of the first four factory projects and prints its level every 100 ms as a row of bars:

```diffstatic {data-lang=kotlin}
val bars = " ▁▂▃▄▅▆▇█"
val l = FloatArray(4800)
val r = FloatArray(4800)

for (slot in 0 until 4) {
    val sim = FM1Sim.create()
    sim.preset(slot)
    repeat(5) { sim.render(l, r, 4800, 48000f) }
    sim.start()
    val levels = (0 until 30).map {
        sim.render(l, r, 4800, 48000f)
        kotlin.math.sqrt(l.indices.sumOf { i -> (l[i] * l[i] + r[i] * r[i]).toDouble() } / (2 * l.size))
    }
    val trace = levels.joinToString("") { bars[(it / 0.14 * 8).toInt().coerceIn(0, 8)].toString() }
    println("project $slot  playing=${sim.isPlaying}  $trace")
    sim.close()
}
```

Offline, `fm1.schedule(pattern, track, cycles, cps)` puts every note of a pattern on its block at once
instead of feeding it as the sound plays, so two renders of the same pattern are the same samples.

The simulator renders at a fixed 44,100 Hz, and `render` converts to the rate you ask for with a
four-point cubic interpolator, which passes the samples through unchanged at 44,100 Hz. A simulator costs
a small fraction of one core: `FM1SoundDocs.realtimeFactor()` measures how many seconds of a playing
project one second of rendering produces on your machine, so you can see how many simulators of your own
(`FM1Sim.create()`, one per extra set of eight tracks) the machine can carry.

!!! note "The simulator, not the hardware"
    The FM-1 itself runs a closed engine library that the open firmware links against, and the web
    build leaves it out: the simulator has its own DX7-compatible FM core beside the open 303, 808,
    pluck, PSG and sampler voices. What you hear is M-VAVE's simulator, which may differ from the
    device.

## Licence

The firmware's application code is GPL-3.0 with a linking exception for M-VAVE's SDK; the voices it
builds on are MIT (Open303, DaisySP, stmlib, emu2149) and Apache-2.0 (MSFA/Dexed). The module Field
ships, its source commit and the command that built it are recorded in
`src/jvmMain/resources/fm1/PROVENANCE.md`. Field adds no vendored code to it: the DX7 voice format,
the patterns and the MIDI handling above are Field's own Kotlin.

## Limitations

* **It is the simulator, not the device.** The FM-1's own FM engine is closed and is not in the
  simulator, and nothing here has been compared against a real FM-1.
* **Timing is to the block.** Notes, controllers and voices land at the start of one of the
  simulator's 128-sample blocks, about 3 ms apart; the firmware has no finer event time.
* **Whole semitones.** Notes are rounded to MIDI's semitones; a drawn score's in-between pitches are
  lost.
* **One mix.** The simulator mixes its eight tracks, with their reverb and delay, into one stereo
  output, so there are no separate track outputs to process in Field. A second `FM1Sim.create()` with
  some tracks muted is the way round it, at the cost of a second simulator.
* **No clock sync.** A Field pattern keeps time with the project through `cps`, from the project's
  tempo, and both start together if started together, but neither follows the other's clock.
* **The sampler can't take Field's audio yet.** The sampler plays its factory samples.
* **The screen is a snapshot.** `screenPng()` shows the LCD when it is called; there is no live panel
  that follows it or takes clicks.
* **The firmware can't be edited from a box.** The module is built once and shipped with Field;
  changing the firmware's C means rebuilding it with the WASI SDK by hand (`PROVENANCE.md`).

## Where to go next

* [Sound paths](sound_paths.md): every way Field makes sound, and which of them play together.
* [Faust](faust.md): the same WebAssembly hosting, for DSP you write yourself.
* [Sound](sound.md): Field's patterns, which `fm1.play` plays.
* [Drawing and notation](drawing_and_notation.md): the drawn scores `toPattern()` reads.
* The starting-point document `fm1_simulator` in [Agent transcripts](agent_transcripts.md) opens these
  boxes on a canvas.
* The source: `src/jvmMain/kotlin/afield/audio/synth/FM1Sim.kt` (the simulator, MIDI, the screen and
  projects), `FM1Patterns.kt` (patterns in, MIDI clips out) and `FM1Voice.kt` (DX7 voices); what was
  left out and why is in `dev_docs/FM1.md`.

What to build next, most useful first:

* **Field audio into the sampler**: lay out the sample bank the firmware reads and write a Field
  render or a recording into it, so a pattern on a sampler track plays Field's own sounds.
* **MIDI clock both ways**: let the FM-1's sequencer follow Field's clock, or Field's patterns follow
  the FM-1's, so tempo changes on either side carry over.
* **A live screen panel**: a canvas panel that redraws the LCD as it changes and turns clicks into
  button and encoder presses.
* **Microtonal tuning**: write the project's tuning block from Field, so drawn pitches between the
  semitones play as drawn.
* **Separate track outputs**: export the firmware's per-track buffers, which needs a rebuild of the
  module.
* **Live-coding the firmware**: edit the voice and sequencer C in a box, rebuild the module, and swap
  it in, carrying the project across with `exportProject` and `importProject`.
