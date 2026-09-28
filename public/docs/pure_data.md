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

That needs `cmake`, `git`, and the network the first time. It clones libpd 0.16.1 (Pd 0.56-5) and
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
box line that holds it, with a suggestion; see [An object Pd cannot create](#an-object-pd-cannot-create).

`PdLaunch` has the same `writeArray`, `readArray`, `arraySize`, `noteOn`, `controlChange` and
`onMidi` as the Pd underneath it, and `patch.pd` is that `PdInstance` when you need the rest.

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

Here is `pdWaveform` on a sawtooth, drawn with its patch. The patch runs in libpd, and no audio
device is opened:

```diffstatic_runner {data-lang=kotlin screenshot="assets/pure_data/offline-saw.png" alt="A line plot titled 'Pd — 20ms': a sawtooth over twenty milliseconds, rising from about -0.35 to about 0.38 and falling sharply, four and a half cycles, with the corners rounded by the lowpass" wait="8" capture="box" open-outputs="true" w="1180" h="360" padding="18" timeout="120" name="pure-data-offline-saw" requires="libpd" embed=true}
val saw = """
#X obj 20 20 phasor~ 220;
#X obj 20 60 -~ 0.5;
#X obj 20 100 lop~ 1500;
#X obj 20 140 *~ 0.8;
#X obj 20 180 dac~;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 3 0 4 0;
#X connect 3 0 4 1;
"""
pdWaveform(saw, seconds = 0.02)
```

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

The drawing is `PdPatchGraph.toFLines()`, which reads the text alone and needs no libpd. Inlets
and outlets are spread across each box as Pd spaces them, so a wire into a right inlet ends at the
right-hand end of the box. How many a box has comes from a table of common vanilla classes
(`PdPatchGraph.KNOWN_PORTS`) and from what the patch wires. This is the drone patch above:

```diffstatic_runner {data-lang=kotlin screenshot="assets/pure_data/drone-patch.png" alt="Six boxes drawn as Pd draws them, each with its inlets as small tabs along the top edge and its outlets along the bottom: r freq above phasor~, above lop~ 1200, above *~ 0.08, above dac~, joined by vertical wires from left outlet to left inlet. r cutoff, to the right, is wired to the tab at the right-hand end of lop~ 1200, its right inlet, and *~ 0.08 has two wires into dac~, one to its left inlet and one to the tab at its right edge." wait="6" capture="box" open-outputs="true" w="900" h="420" padding="18" timeout="120" name="pure-data-drone-patch" embed=true}
val drone = """
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
"""
lines(afield.pd.PdPatchGraph.parse(drone).toFLines(), channel = "patch", w = 460.0, h = 230.0, fitToSize = true)
```

The editor's line notes say what the object on a line is wired to. On an `[s]` or `[r]` they also give
the last value that went through that name. Here the round-trip patch has been launched and its line
`in 41` run; the cursor is on `[s out]`:

```diffstatic {data-lang=text}
:::static
// Launch, then run the line below: [+ 1] sends 42 to [s out].
in 41
#N canvas 0 0 400 250 12;
#X obj 20 20 r in;
#X obj 20 60 + 1;
#X obj 20 100 s out;
#X obj 120 100 print out;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 3 0;
```

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" clip-selector=".monaco-editor-container" screenshot="assets/pure_data/line-note.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/pd_line_note_roundtrip.yaml" capture-box="pd_doc_roundtrip" requires="libpd" padding="10" wait="0" timeout="120" embed=true hidden=true name="pure-data-line-note" alt="A Pd box in Field's editor holding the round-trip patch: a comment, the message line in 41, then the #N and #X statements for r in, + 1, s out and print out. At the editor's foot the line note for line 6 reads: [s out] is object 2; fed by [+ 1] (outlet 0 → inlet 0). Last on 'out': 42.0."}
// The setup transcript launched the box and ran its line `in 41`. Put the cursor on `[s out]`
// (box line 6) and wait for the editor to show that line's note. The still is taken here, clipped to
// the editor (clip-selector), because a capture after the script re-opens the editor and resets
// the cursor, and the note goes with it.
ui.openEditor("pd_doc_roundtrip")
ui.waitForSelector(".monaco-editor", 15000.0)
ui.pause(1000)
// A shorter editor, so the note at its foot sits under the patch rather than a page below it.
ui.eval("var e = window.globalEditor; var c = e._domElement; c.style.height = '600px'; e.layout(); return 'ok';")
ui.pause(300)
ui.eval("var e = window.globalEditor; e.focus(); e.setPosition({lineNumber: 6, column: 1}); e.revealLine(6); return 'ok';")
ui.waitForSelector(".afield-line-note", 10000.0)
ui.pause(600)
```


### Sending to a Pd box from Kotlin

A Kotlin box can reach the patch a Pd box is playing. `pdBox` finds the box by name, and `pdSend`
sends to an `[r]` in its patch, just as running the line `freq 330` in the Pd box does:

```diffstatic {data-lang=kotlin}
:::static
pdBox("drone").pdSend("freq", 330)

// Launched, this plays an arpeggio into [r freq] until the box stops.
val drone = pdBox("drone")
val notes = listOf(110, 165, 220, 330, 440, 330, 220, 165)
var i = 0
loop {
    drone.pdSend("freq", notes[i++ % notes.size])
    delay(250)
}
```

Here a Pd box named "drone" is playing a patch that starts with `[r freq]`, and a Kotlin box beside
it has run `pdBox("drone").pdSend("freq", 330)`. With the cursor on the drone's `[r freq]` line,
the line note shows what arrived:

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" clip-selector=".monaco-editor-container" screenshot="assets/pure_data/send-from-kotlin.png" macro-file="../../dev_docs/agents/transcripts/docs_capture/pd_send_from_kotlin.yaml" capture-box="pd_doc_drone" requires="libpd" padding="10" wait="0" timeout="120" embed=true hidden=true name="pure-data-send-from-kotlin" alt="A Pd box named drone in Field's editor, its patch starting #X obj 20 20 r freq, then phasor~ 110, lop~ 1200, *~ 0.05 and dac~. At the editor's foot the line note for line 2 reads: [r freq] is object 0; feeds [phasor~ 110] (outlet 0 → inlet 0). Last on 'freq': 330.0."}
// The setup transcript launched "drone", then ran the Kotlin box `pdBox("drone").pdSend("freq", 330)`.
// Put the cursor on the drone's `[r freq]` (box line 2) and wait for its note; the still is taken
// here, clipped to the editor, as in the figure above.
ui.openEditor("pd_doc_drone")
ui.waitForSelector(".monaco-editor", 15000.0)
ui.pause(1000)
// A shorter editor, so the note at its foot sits under the patch rather than a page below it.
ui.eval("var e = window.globalEditor; var c = e._domElement; c.style.height = '600px'; e.layout(); return 'ok';")
ui.pause(300)
ui.eval("var e = window.globalEditor; e.focus(); e.setPosition({lineNumber: 2, column: 1}); e.revealLine(2); return 'ok';")
ui.waitForSelector(".afield-line-note", 10000.0)
ui.pause(600)
```

Any number works, and an `Int` arrives as a Pd float. `pdSend("go")` with nothing after the name is
a bang, several numbers are a list, and a string first is a selector, so `pdSend("voice", "set", 3)`
sends `set 3`. The Pd box has to be playing. If it is not, `pdSend` throws and says so, and a launched
loop stops on that line.

### An object Pd cannot create

Pd reports a misspelt object on its console and leaves it out of the patch. Field marks the line it
is on and suggests what was probably meant. This Pd box was launched with `[osc 440]` on line 3:

```diffstatic_runner {data-lang=pd screenshot="assets/pure_data/typo.png" capture="editor" outputs="inline" expect-error=true requires="libpd" wait="4" padding="10" timeout="120" embed=true name="pure-data-typo" w="900" h="260" alt="A five-line Pd box in Field's editor. Under line 3, #X obj 20 20 osc 440, a red error reads: Pd couldn't create [osc 440] — did you mean [osc~ 440]? Below the last line, Pd's own console echo: verbose(0): osc 440, error: ... couldn't create."}
// [osc 440] is not a Pd object; [osc~ 440] is.
#N canvas 0 0 400 250 12;
#X obj 20 20 osc 440;
#X obj 20 60 dac~;
#X connect 0 0 1 0;
```

The same message, and the same mark on the box's own line, come from `pd("""…""")` in a Kotlin box. The
suggestions come from `pd-objects.txt`, which the libpd build writes from the same pinned sources it
compiles. It lists every object and alias Pd registers (297 at libpd 0.16.1), so a suggestion is
always something this Pd can make. `PdObjects.suggest` tries the signal or control twin first (`osc` for
`osc~`, `metro~` for `metro`), then a difference of case, then the nearest names within two edits.

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

??? aside "What the figures need, and how the editor ones are made"

    Every figure here is captured by running real code on the docs build machine. All but the
    patch drawing need libpd there: those fences say `requires="libpd"`, and
    `./gradlew buildDocsNativePrerequisites` builds it with the rest. The drawing reads text only.

    The typo figure is an ordinary editor capture of a Pd box: the runner puts the fence under a
    `mainLanguage` root (`tools/field_languages.py` lists `pd` for that) and launches it, and
    `expect-error=true` makes the missing error a failure rather than the error itself. The two
    line-note figures need a patch that is already playing and a cursor on a particular line, so
    each has a setup transcript in `dev_docs/agents/transcripts/docs_capture/` (launch the Pd box,
    run a message line or the Kotlin sender) and a short drive script that opens the editor, moves
    the cursor and waits for the note to appear. The note's text is the server's answer for that
    line, so a wrong value would be photographed as wrong.

    The numbers elsewhere on this page come from `PdTest` in `src/jvmTest`, which skips when libpd
    is not built, and the loadable scene is `dev_docs/pd/pure_data_demo.yaml`.

For where Pd sits among Field's other ways of making a sound, see
[Making a Sound, Every Path There Is](sound_paths.md).
