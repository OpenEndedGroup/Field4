# FM-1 simulator

The [M-VAVE FM-1](https://open.m-vave.com/fm1-workbench) is a pocket groovebox: eight tracks of 64 steps,
with DX7-style FM, a 303 bass line, 808 drums, a plucked string, a chip-tune PSG and a sampler. Its
firmware is open source, and the same source builds a simulator that runs in a web page. Field runs that
simulator in its audio engine, so a box can play the FM-1's factory projects, press its buttons and play
its keys.

There is nothing to install and no hardware involved. The simulator comes with Field as a WebAssembly
module, built from the [open firmware](https://github.com/m-vave/fm1_open).

## A box that plays it

This box loads factory project 1, starts the sequencer, and returns the simulator as its last
expression, so launching the box puts it in the mix:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared

fm1.preset(1)                     // a factory project: change the slot (0..3) and re-run
if (!fm1.isPlaying) fm1.play()    // PLAY/STOP is a toggle, so only press it when stopped
fm1.level(0.8)

fm1.node
```

`FM1Sim.shared` is one simulator for the whole of Field. Re-running the box loads the new project into
it while the sequencer keeps its state, so you can change the slot and hear the next project without
the transport stopping. Stopping the box takes it out of the mix.

Factory project 0 sounds like this, six seconds from the moment PLAY is pressed:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/project-0.wav" audio-spec="project-0" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-project-0"}
// FM1SoundDocs.renderWav("project-0", …): factory project 0, PLAY tapped, six seconds offline
```

And factory project 1, the one the box above plays:

```diffstatic_runner {data-lang=kotlin audio="assets/fm1/project-1.wav" audio-spec="project-1" audio-renderer="afield.audio.synth.FM1SoundDocs" embed=true hidden=true name="fm1-project-1"}
// FM1SoundDocs.renderWav("project-1", …): factory project 1, PLAY tapped, six seconds offline
```

## Playing the panel from code

Every call queues a control change that the simulator picks up at the start of its next block, so it
is safe to call from a box while the sound plays.

| call | what it does |
| --- | --- |
| `preset(slot)` | load a factory project (0 is "NEON KEYS") |
| `play()` | press PLAY/STOP |
| `whiteKey(n, blocks = 40, afterBlocks = 0)` | play white key `n` (1 to 16) for that many 128-frame blocks, optionally starting later; the white keys are also the step keys 01 to 16 |
| `tap(FM1Sim.Button.REC)` | press and release a panel button: `OCT_DOWN`, `OCT_UP`, `FX`, `SEL`, `ENV`, `LFO`, `EDIT`, `GLO`, `HOME`, `SAVE`, `ARP`, `SEQ`, `PLAY`, `REC` |
| `button(index, down)`, `key(index, down)` | hold or release a button or any of the 27 keys yourself |
| `encoder(index, delta)` | turn one of the seven encoders by some detents |
| `step(n)` | toggle step `n` of the selected track |
| `level(0.0..1.0)` | the output-level knob |
| `panic()`, `release()` | silence every voice; let go of everything held |
| `isPlaying` | whether the sequencer is running |

A button press has to last at least one of the firmware's 10 ms control scans to be seen, which is why
`tap` and `whiteKey` hold for a number of blocks rather than pressing and releasing at once.

This plays the first eight white keys up the keyboard on top of whatever project is running, a
quarter of a second apart. `afterBlocks` schedules each key on the simulator's own clock, 86 blocks
(a quarter of a second) after the one before, so the box returns at once:

```diffstatic {data-lang=kotlin}
val fm1 = FM1Sim.shared
for (n in 1..8) {
    fm1.whiteKey(n, blocks = 60, afterBlocks = (n - 1) * 86)
}
```

## Rendering it offline

`FM1Sim.create()` makes a simulator of its own, which you pull audio from directly and close when you
are done. Nothing here needs an audio device, so it works in a test or a docs build. This renders three
seconds of each factory project and prints its level every 100 ms as a row of bars:

```diffstatic {data-lang=kotlin}
val bars = " ▁▂▃▄▅▆▇█"
val l = FloatArray(4800)
val r = FloatArray(4800)

for (slot in 0 until 4) {
    val sim = FM1Sim.create()
    sim.preset(slot)
    repeat(5) { sim.render(l, r, 4800, 48000f) }
    sim.play()
    val levels = (0 until 30).map {
        sim.render(l, r, 4800, 48000f)
        kotlin.math.sqrt(l.indices.sumOf { i -> (l[i] * l[i] + r[i] * r[i]).toDouble() } / (2 * l.size))
    }
    val trace = levels.joinToString("") { bars[(it / 0.14 * 8).toInt().coerceIn(0, 8)].toString() }
    println("project $slot  playing=${sim.isPlaying}  $trace")
    sim.close()
}
```

The simulator renders at a fixed 44,100 Hz; `render` converts to the rate you ask for.

!!! note "The simulator, not the hardware"
    The FM-1 itself runs a closed engine library that the open firmware links against, and the web
    build leaves it out: the simulator has its own DX7-compatible FM core beside the open 303, 808,
    pluck, PSG and sampler voices. What you hear is M-VAVE's simulator, which may differ from the
    device.

## Licence

The firmware's application code is GPL-3.0 with a linking exception for M-VAVE's SDK; the voices it
builds on are MIT (Open303, DaisySP, stmlib, emu2149) and Apache-2.0 (MSFA/Dexed). The module Field
ships, its source commit and the command that built it are recorded in
`src/jvmMain/resources/fm1/PROVENANCE.md`.

## Limitations

* **It is the simulator, not the device.** The FM-1's own FM engine is closed and is not in the
  simulator, and nothing here has been compared against a real FM-1.
* **No screen yet.** The simulator draws the FM-1's LCD into memory, but Field does not show it, so
  editing a sound means knowing which buttons and encoders to turn.
* **Keys are by index.** `whiteKey` covers the sixteen white keys; the other eleven are reached with
  `key(index, down)`, and no call maps them to pitches yet.
* **No MIDI in or out.** The simulator has a MIDI router, but Field does not connect it to its MIDI
  devices or to patterns, so a pattern drives the FM-1 only through key and button calls.
* **Projects and sounds don't persist.** Projects you save inside the simulator live in its memory
  and are gone when Field quits; importing and exporting projects or DX7 banks is not wired up.
* **One fixed rate.** The simulator runs at 44,100 Hz and is resampled linearly to Field's rate,
  which softens the top octave slightly at 48 kHz.
* **The firmware can't be edited from a box yet.** The module is built once and shipped with Field;
  changing the firmware's C means rebuilding it with the WASI SDK by hand (`PROVENANCE.md`).

## Next steps

* Show the LCD and the step lights on the canvas, from the simulator's own pixel and state output.
* Connect MIDI: Field's patterns and MIDI devices to the simulator's tracks, and its MIDI out back to
  Field.
* Load and save projects and DX7 voice banks as files beside the document.
* Live-code the firmware: edit the voice and sequencer C in a box, rebuild the module, and swap it
  into the running audio engine between blocks.
* Map all 27 keys to pitches, so a pattern can play notes by name.

## Where to go next

* [Sound paths](sound_paths.md): every way Field makes sound, and which of them play together.
* [Faust](faust.md): the same WebAssembly hosting, for DSP you write yourself.
* [Sound](sound.md): patterns, which can drive the simulator's keys and buttons from a box.
