# Pure Data

[Pure Data](https://puredata.info) is Miller Puckette's visual patching language: boxes wired to
boxes, audio and messages flowing down the wires. Field runs Pd patches through
[libpd](https://github.com/libpd/libpd), Pd's engine with the GUI removed. A patch plays through the
same audio engine as Faust and `patSynth`, Field sends messages to its `[r]` objects, and whatever its
`[s]` objects send comes back to Kotlin.

A Pd patch is plain text, so a patch can be written straight into a box as well as opened from a
`.pd` file drawn in Pd itself.

## Building libpd

libpd is a C library, and Field does not ship a binary of it. Build it once:

```diffstatic {data-lang=bash}
./gradlew buildLibpdNative
```

That needs `cmake`, `git`, and the network the first time. It clones libpd 0.16.1 (Pd 0.55-2) and
checks it against a pinned commit, then builds into `build/field-libpd/`. The source is cached per
user, so other checkouts and worktrees reuse it. Until it is built, the calls on this page fail with
an error that says how to build it.

## A patch in a Kotlin box

```diffstatic {data-lang=kotlin}
:::static
val patch = pd("""
#X obj 20 20 r freq;
#X obj 20 60 osc~ 220;
#X obj 20 100 *~ 0.1;
#X obj 20 140 dac~;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 2 0 3 1;
""")
controlGroup { slider("freq", 100.0, 1000.0, initialValue = 220.0) { patch.send("freq", it) } }
patch
```

`pd` opens the text as a patch and returns a `PdLaunch`. Returning the launch as the box's last
expression makes it sound; stopping the box stops the sound and frees the Pd. The `#N canvas`
header is optional and the text may be indented.

Each launch gets a Pd of its own, so two boxes that both use `[r freq]` do not hear each other. The
patch's `$0` is `patch.dollarZero` if you need names that are local to one copy.

`pd(File("…/my.pd"))` opens a file instead. Abstractions next to it are found, the way they are in Pd.

### A patch as an effect

Pass `input` and the patch's `[adc~]` hears a Field signal, left and right, so the patch processes
sound instead of making it:

```diffstatic {data-lang=kotlin}
:::static
pd("""
#X obj 20 20 adc~;
#X obj 20 60 lop~ 600;
#X obj 20 100 dac~;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 2 1;
""", input = SawOsc(Param(110f)).pan())
```

What goes in on a block comes out on the same block; libpd adds no latency of its own.

### Messages both ways

`send`, `bang`, `symbol`, `list` and `message` reach `[r name]`:

```diffstatic {data-lang=kotlin}
:::static
patch.send("freq", 330)
patch.bang("trigger")
patch.list("xy", 0.2, 0.8)
patch.message("voice", "set", 3)
```

`receive` goes the other way. The patch sends to `[s out]`, and Field calls the lambda:

```diffstatic {data-lang=kotlin}
:::static
patch.receive("out") { m -> println("the patch said ${m.float}") }
```

The lambda runs on Field's main thread, so it can touch the box. It does not run on the audio thread.
`patch.lastValue("out")` gives the last message on a received name without a callback.

Everything the patch prints (a `[print]`, a Pd error) goes to the box's output with `Pd:` in front.
If Pd cannot create an object, `[osc 440]` where `[osc~ 440]` was meant, the error is marked on the
line that holds it.

### Sliders from the patch's own

A patch drawn in Pd usually has its own `hsl`, `vsl`, `tgl` and `nbx` objects. `pdControls(patch)`
turns each one that has a receive name into a Field control with the range and label from the patch,
and each control sends to that receive name. The Pd slider moves with it and its outlet fires as if it
had been dragged. That is the same idea as `faustControls`.

### Arrays

`writeArray`, `readArray` and `arraySize` work on any Pd array, `[table name]` or `#X array`:

```diffstatic {data-lang=kotlin}
:::static
// A wavetable for [tabosc4~ wave]: 2048 points plus the three guard points it wants.
patch.writeArray("wave", FloatArray(2051) { i -> sin(2 * PI * (i - 1) / 2048).toFloat() })
val recorded = patch.readArray("scope")   // what a [tabwrite~ scope] captured
```

## Rendering without an audio device

`PdInstance` is the Pd underneath a launch, and it can be used directly. `renderOffline` pulls
samples out of it without opening an audio device, which is how the tests measure it:

```diffstatic {data-lang=kotlin}
:::static
val pd = afield.pd.PdInstance(sampleRate = 48000)
pd.openPatchText("#X obj 20 20 osc~ 440;\n#X obj 20 60 dac~;\n#X connect 0 0 1 0;\n#X connect 0 0 1 1;")
val (left, right) = pd.renderOffline(1.0)
pd.close()
```

The sine above gives 440 upward zero crossings in that second, and a peak of the amplitude it was
given, to within 0.002. `renderPdWav(file, patchText, seconds)` writes the same thing to a 16-bit
stereo `.wav`, and `pdWaveform(patchText)` plots a few milliseconds in the box.

Messages sent while nothing is rendering wait for the next block. `pd.flush()` delivers them at once
and hands Pd's replies to their callbacks, so a message round trip can be checked with no audio at all.

## Pd as a box language

Set a box's Main Language to **Pure Data** (the command palette has "Set Main Language: Pure Data")
and the boxes connected under it hold patches instead of Kotlin. Such a box's text *is* the patch:

```diffstatic {data-lang=text}
:::static
// Launch the box, then select one of these lines and run it on its own:
freq 330
cutoff 3000
#N canvas 0 0 500 320 12;
#X obj 20 20 r freq;
#X obj 20 60 phasor~;
#X obj 20 100 lop~ 1200;
#X obj 140 60 r cutoff;
#X obj 20 140 *~ 0.08;
#X obj 20 180 dac~;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 3 0 2 1;
#X connect 2 0 4 0;
#X connect 4 0 5 0;
#X connect 4 0 5 1;
```

Running or launching the box opens the patch, draws it in the box's `patch` output, and plays it
until the box stops. Running it again replaces the patch. A line that does not start with `#` is a
message, written as a Pd message box writes one after a `;`: the receiver, then the value.
`freq 330` sends 330 to `[r freq]`, `go` bangs `[r go]`, `voice set 3` sends `set 3`. Those lines and
`//` comments are removed before Pd reads the patch, and selecting one and running it sends it.

The editor's line notes say what the object on a line is wired to. On an `[s]` or `[r]` they also give
the last value that went through that name.

## What is not here

- **Pd's GUI.** libpd has none. A patch opened here cannot be edited by dragging; edit the text, or
  draw it in Pd and open the file.
- **Externals beyond Pd's `extra`.** `bonk~`, `fiddle~`, `sigmund~` and the other objects
  in Pd's own `extra` folder are built in. Compiled externals from Deken are not loaded.
- **A MIDI device.** `noteOn` and `controlChange` reach a patch's `[notein]` and `[ctlin]`, and
  `onMidi` hears its `[noteout]` and `[ctlout]`, with channels counted from 0 (`[noteout 1]` is
  channel 0). Nothing connects those to a hardware port yet; that is a few lines of Kotlin per box.
- **One output per launch.** Every patch opened in one Pd sums into that Pd's `[dac~]`; the launch's
  `gain` applies to the sum.

??? aside "Why this page has no figures"

    Every capture in these docs is produced by running the real code on the docs build machine, and
    that machine does not build libpd: `buildDocsNativePrerequisites` does not include
    `buildLibpdNative` yet. So the numbers above come from `PdTest` in `src/jvmTest`, which skips
    when libpd is missing, and the loadable scene is `dev_docs/pd/pure_data_demo.yaml`. Adding
    libpd to the docs prerequisites is what would let this page carry a real waveform and a real
    patch drawing.

For where Pd sits among Field's other ways of making a sound, see
[Making a Sound, Every Path There Is](sound_paths.md).
