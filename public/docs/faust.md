# Faust

[Faust](https://faust.grame.fr) is a functional language for signal processing. You write what the
signal *is* and its compiler turns that into efficient code. Field embeds the compiler. A `#faust`
region inside an ordinary Kotlin string is compiled, instantiated and playing through Field's audio
engine, with the sliders it declares showing up as real Field controls.

Nothing is installed to make this work. Not `faust`, not LLVM, not a C++ toolchain. The compiler
travels with Field as 5.3MB of platform-independent WebAssembly, and the bill arrives as compile
speed, which [Nothing to install](#nothing-to-install) sets out at the end of the page.

## A box that is a signal processor

```diffstatic {data-lang=kotlin}
:::static
faust("""
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 220, 50, 2000, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1200, 50, 8000, 1);
gain   = hslider("gain", 0.2, 0, 1, 0.001);
process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * gain <: _,_;
""")
```

Returning the result as the box's last expression is what ties the sound to the box. The box stays lit
while the processor runs, and stopping the box stops the sound *and* releases the DSP, by the same
path any other Field audio launch takes.

Three sliders were declared, so three sliders appear in the box's control area. Nobody wrote them:
they are the program's own `hslider` declarations, with the ranges, defaults and steps it asked for.
Move one and the value is written straight into the running DSP.

### The marker is not part of the program

`#faust` marks the region. In Sonic Pi the marker is harmless: `#` starts a Ruby comment, and
`#sonicpi` rides along into the program Sonic Pi is handed. **`#` is not a comment in Faust**, and a
`#faust` line is a syntax error. Field strips it before compiling and shifts every line number the
compiler reports back onto the box's own lines. That is why an error lands on the line you wrote it on.

## Errors land on the line

Faust's diagnostics are good, and they arrive as markers:

```diffstatic {data-lang=kotlin}
:::static
faust("""
#faust
import("stdfaust.lib");
process = os.sawtoothX(440);
""")
```

puts `undefined symbol : sawtoothX` on the `process` line. The compile also throws, so the box does
not sit there looking as though it succeeded.

The editor adds a second, faster layer that does not need the compiler at all: unbalanced brackets,
unterminated strings and comments, a library prefix that `stdfaust.lib` does not bind, a member that
is not documented in the library you named, and a region with no `process` definition. Those appear as
you type. Everything else waits for the real compiler, which is ~600ms away.

## Completion knows the standard library

Faust's standard library is about fifty `.lib` files documenting themselves in a rigid comment
convention. `tools/generate_faust_vocabulary.py` reads that convention *out of the compiler Field
ships* and writes `faust-vocabulary.json`: **1008 functions**, 990 with a summary and 940 with a signature,
across 37 library prefixes.

So completion is qualified, which is the way Faust is actually written:

* `os.` offers the 78 oscillator functions, with signatures — not a flat list of a thousand names.
* `fi.` offers 109 filters, `ba.` 90 basics, `pm.` 150 physical-modelling functions.
* A bare caret offers the language itself: `process`, `with`, `letrec`, the UI primitives, and the
  library prefixes.
* The UI primitives insert as snippets, because their argument order is the thing everyone
  misremembers: Faust puts `init` *before* `min`.

Hovering a qualified name shows its signature, its summary and its parameters, taken from the same
generated file. Because the vocabulary is extracted rather than typed, it cannot drift from the
compiler: bump the vendored version and re-run the script.

## The controls are the program's own

A Faust program declares its interface in the source, and Field turns that declaration into an ordinary
Field control group: `Box.controlGroup`, the same call any other box would make. Nothing about it is
Faust-specific. Whatever a control group can do, a Faust program's declared interface does too,
including being served to a phone or an iPad by the controller websocket.

!!! note "Remote controllers are untested here"
    Faust controls reach a remote controller because they are registered through the same
    `registerControllerControlGroup` path as every other control, not because of anything in the
    Faust binding. That follows from the code; nobody has put a Faust box's sliders on a tablet and
    moved them.

The mapping is unusually direct. Faust's `hslider(label, init, min, max, step)` and Field's
`slider(label, min, max, step, initialValue)` carry the same five values. And Field's control group
already reuses a control's value across box reruns, which is exactly what live-coding a DSP needs:
recompiling must not snap every slider back to its default.

| Faust declares | Field shows |
|---|---|
| `hslider`, `vslider`, `nentry` | a slider over the declared range |
| `checkbox` | a checkbox |
| `button` | a checkbox marked `(hold)` — see below |
| `hbargraph`, `vbargraph` | nothing; read it with `launch.meter("level")` |
| `hgroup`, `vgroup`, `tgroup` | a qualified label, `filter/cutoff` |
| `[unit:Hz]` | appended to the label |

Three of those are compromises rather than finished work:

* **`button` is momentary in Faust and Field's checkbox latches.** Holding the checkbox holds the
  gate, which is usable, but it is not the same thing. From code, `launch.press("gate")` is the real
  momentary trigger: press and release are queued in order, so both land even inside one audio block.
* **`hbargraph`/`vbargraph` are outputs rather than controls.** They are not in the control group at
  all; `launch.meter("level")` reads the most recent value, sampled once per audio block.
* **`[scale:log]` is not honoured by the slider's travel.** The declared range is kept and the numbers
  are right, but a log frequency slider moves linearly. Field's slider has no law parameter; the
  metadata is parsed and readable on `FaustControl.scale`.

```diffstatic {data-lang=kotlin}
:::static
val voice = faust("""
#faust
import("stdfaust.lib");
gate = button("gate");
freq = hslider("freq [unit:Hz]", 220, 50, 1000, 0.01);
env  = en.adsr(0.01, 0.1, 0.7, 0.4, gate);
process = os.sawtooth(freq) * env * 0.3 <: _,_;
""")

// Play it from code rather than by hand.
voice.press("gate", holdMs = 250)
voice["freq"] = 330.0
voice
```

## A pattern plays it

A Faust program can also be an *instrument* rather than a drone: N instances of the same compiled DSP,
allocated to notes, with the notes coming from Field's own [pattern language](sound.md).

```diffstatic {data-lang=kotlin}
:::static
val inst = faustPoly("""
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = os.sawtooth(freq) : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""", voices = 8)

inst.play(
    pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]"),
    cps = 0.5,
    automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums())
)

inst
```

Nothing is recompiled to make eight voices. A Faust wasm factory is built with internal memory, so
`compute(0, …)` addresses a struct at offset 0 of each instance's own memory: eight
`WebAssembly.Instance`s of one module are eight independent voices, and they all live in one GraalVM
context.

Note the second pattern. `Pat` is a rhythm of *anything*, so the filter is patterned exactly as the
notes are, and both go through the same event timeline.

### What it sounds like, at eight voices and at one

Here is that example, rendered offline. Then the same notation with `voices = 1`.

```diffstatic_runner {data-lang=kotlin audio="assets/faust/poly-chord-eight-voices.wav" audio-spec="poly-chord-eight-voices" audio-renderer="afield.four.synth.FaustPolySoundDocs" embed=true hidden=true name="faust-poly-eight-voices"}
// FaustPolySoundDocs.render("poly-chord-eight-voices")
// faustPatternRender(program, pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]"),
//                    cycles = 2, cps = 0.5, voices = 8, automation = cutoff steps)
```

**Eight voices.** Four triads, one per cycle. Three notes of each chord sound together, which is three fundamentals in a band that can hold nothing else.

```diffstatic_runner {data-lang=kotlin audio="assets/faust/poly-chord-one-voice.wav" audio-spec="poly-chord-one-voice" audio-renderer="afield.four.synth.FaustPolySoundDocs" embed=true hidden=true name="faust-poly-one-voice"}
// FaustPolySoundDocs.render("poly-chord-one-voice")
// the identical call with voices = 1
```

**The same notation, one voice.** Identical pattern, program and gain. The three notes of a chord arrive on the same frame, so two of them steal the only voice there is — and what you hear is the last of the three, with a click where the voice turned over.

The difference is measurable as well as audible: three of the chord's notes sound at once at
`voices = 8` and one at `voices = 1`, counted as spectral peaks in the band where only fundamentals
can sit. Counting attacks would not tell you that, because a single voice stealing itself produces
exactly the same number of them. Both clips are asserted by `synth.faust.poly.sound`, and the peak
counts, the band and what limits them are in `dev_docs/FAUST_MEASUREMENTS.md`.

`steals` counts same-frame collisions, not notes that ran out of voices, so it reports far fewer
than you would expect from the note count. A voice whose gate has fallen and whose release has
finished is *finished*, and a finished voice is reused rather than stolen — so on this pattern only
the second and third note of each chord, which arrive on the very same frame as the first, steal
anything at all. Read it as a count of simultaneity when you size `voices`. The worked arithmetic
for this render is in `dev_docs/FAUST_MEASUREMENTS.md`. The one-voice clip holds the last note of
each chord as notated, not the first.

### Play it yourself: the `faust_polyphony` starting point

Everything on this page ships as a loadable document — six boxes, on the welcome screen under
**Starting points**, or straight from here:

[Open the starting point](/offline.html?file=/field4/assets/agent_transcripts/faust_polyphony/metadata.edn)

*Faust instrument* is the live one and the only box that opens an audio device: `faustPoly(…, voices = 8)`
playing the pattern above, returned as the box's last expression so the box stays lit while it plays
and stopping it stops the sound, the feeder thread and the GraalVM context. *Offline, with a plot* is
the path the clips above came from, safe on any machine. *Three notes at once* counts the sounding
fundamentals for you, at four voices and at one. *Onsets, in samples* puts every notated frame
beside the frame the audio attacks on. *What it refuses* is a program with no `gate`. *When it runs
out of voices* renders the same over-subscribed passage at `stealReleaseFrames = 1` and at `1440`,
with both WAVs playable and the line to edit marked — the knob whose two ends are measured
[below](#stealing-clicks-and-the-stealreleaseframes-trade).

Five of the six are replayed on every CI run by `examples.demo-transcript.exec`, so the boxes cannot
rot silently; the document itself is regenerated from the transcript by
`examples.agent-transcripts.materialize`, which fails if the committed copy has drifted. See the
[gallery entry](agent_transcripts.md) for what each box prints.

### The notes land on the sample, not on the block

A 128-frame block at 48 kHz is 2.67 ms. Quantising a note onset to it is audible: it is the difference
between a groove and a grid. So `FaustVoiceBank.render` enters the GraalVM context **once** and then walks
the block in chunks that end where the next event begins: apply the writes due at this exact frame,
`compute` up to the next event, repeat.

This is also why [`FaustDsp.setParam`](#reference)'s queue is *not* used on this path: it drains once per
`render` call, which is block granularity by construction. A per-block write would put a note up to 110
frames — 2.3 ms — from where it was notated.

Offline the first non-zero sample of a note *is* the notated frame, exactly, and so is a patterned
parameter change. `dev_docs/FAUST_MEASUREMENTS.md` has the numbers and what limits them.

### `freq`, `gate`, `gain`: found by label, refused when absent

Faust's own polyphonic convention is `freq`/`gate`/`gain`, the three names `-nvoices` looks for; a
program written for `faust2jaqt -nvoices 8` plays here unchanged. They are discovered out of the program's
declared UI **by label and never by index**, because Faust lists controls *alphabetically within each
group*: in the program above, control index 0 is `cutoff`. An allocator that wrote pitch to index 0 would
sweep a filter and never change a note.

A program that does not declare them is refused, with the message a person can act on rather than silence:

```diffstatic {data-lang=text}
Faust: 'box_a1b2c3' cannot be played polyphonically — a polyphonic program must declare `gate`,
which is Faust's own convention (the same names `-nvoices` looks for). It declares:
cutoff (hslider), freq (hslider).
  gate = button("gate");
  … and gate the envelope with it, e.g. en.adsr(0.005, 0.08, 0.6, 0.05, gate)
```

Silence is the hardest bug to read, and writing to control 0 and hoping is how you get it.

The control group a polyphonic launch generates contains the *shared* controls only: `cutoff` here.
`freq`, `gate` and `gain` are absent: the notes write them on the sample a note begins, and a
slider fighting the notes for them would look broken.

### How a note claims a voice

A note claims a voice in this order:

1. a **finished** voice — gate down *and* its own output below `idleThreshold`. That is the Faust
   architecture layer's own answer, watching the output, rather than a guess at an envelope the host cannot
   see: Faust does not report its release time, so guessing it would be wrong for every program but the one
   it was tuned on.
2. else a **released but still ringing** voice, counted as `tailCuts` — audible as a shortened tail.
3. else **steal the oldest sounding voice**, counted as `steals`. A note is never dropped; an instrument
   that silently ignores a note is worse than one that audibly runs out.

`peakConcurrency` is how much polyphony a pattern actually asked for: the most voices that ever
sounded together over a render.

#### Stealing clicks, and the `stealReleaseFrames` trade

A steal clicks, because Faust's `en.adsr` resets to zero on a trigger rather than continuing from the
level it was at. The one-sample gate dip that gives the new note its rising edge therefore drops the
outgoing note's amplitude to zero in a single sample. The dip is not optional: writing `freq` to a voice
whose gate is already up does not restart the envelope, so without it the stolen voice glides in at the old
level and no note is heard at all.

`stealReleaseFrames` trades the click against lateness. The default of `1` leaves the new note on time and
the click measurable; `1440` (30 ms, longer than this program's release) all but removes the click and puts
the note 30 ms late. Both ends are asserted by `synth.faust.poly`. Stealing is an overload condition either
way.

### Rendering a pattern offline

`faustPatternRender` renders a pattern through a bank with no audio device and no window, in 128-frame
blocks, and through the *same* `render` the audio thread calls, so what you measure is what would be
heard. `Box.showFaustPattern` is the one-liner that puts a player and a waveform in a box's output.

Three measurement helpers come with it:

* `faustOnsets` — the frame of every attack. A burst is a run of samples that is not quiet, allowing
  quiet stretches shorter than `minGapMs`; the onset is the burst's first sample.
* `faustSilentGaps` — **run this before believing any onset count.** A pattern rendered at `sustain = 1.0`
  is a continuous tone with no attacks at all, and an onset detector pointed at one will report envelope
  ripple as notes. `FAUST_PATTERN_SUSTAIN` is 0.9 rather than 1.0 for exactly this reason.
* `faustSpectrumPeaks` — the only thing here that can tell polyphony from monophony. Counting attacks
  cannot: a single voice stealing itself produces the same number of them.

### What eight voices cost

Eight voices render a 128-frame block in **16.9 µs** against a 2667 µs budget — 158× realtime — and the
cost is linear in voices, which is what a bank of independent DSPs should be. Sixteen voices build in 6 ms.
Splitting a block at eight event boundaries costs about 2.2× an undisturbed one and is still 81× realtime
with a note starting every 0.33 ms, so sub-block scheduling is not where your budget goes.

A bank hosts every voice in one GraalVM context, unlike `FaustDsp`, which gives each voice its own. The
measurements behind that choice, and the ~0.9 KB per voice per block that `render` still allocates, are in
`dev_docs/FAUST_MEASUREMENTS.md`.

### Calling in from another thread

`render` takes no lock. `schedule` and `automate` hand work over through lock-free queues that `render`
drains once at the top of each block, and the audio thread owns the ordered queues outright. Producers pay
the node allocation on their own thread, where it is harmless.

The one thing that costs you: work handed over *during* a `render` call is seen by the next block, at most
2.67 ms later. Notes are scheduled ahead of their frame anyway, and one that arrives late is played
immediately and counted in `lateNotes`. `clearSchedule()` takes effect within a block rather than
instantly, for the same reason.

## The block diagram

Faust is a language about signal flow, and its block diagram *is* the signal flow. Field can show it,
generated by the same embedded compiler, with no `faust -svg` on the command line:

```diffstatic {data-lang=kotlin}
:::static
faust("""
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(2, 1200) * 0.2 <: _,_;
""", diagram = true)
```

Faust emits a set of linked SVGs, one per sub-expression; the top-level `process.svg` is the one shown.
The links between them are file references, which Field does not follow, so you see the top level only.

## Effects, not just synths

A program that declares inputs is an effect. Give it something to process:

```diffstatic {data-lang=kotlin}
:::static
// `input` takes any StereoRenderable — another Faust program, a Field oscillator, a pattern voice.
val dry: StereoRenderable = StereoSignal(SawOsc(Param(110f)) * 0.2f, SawOsc(Param(110.5f)) * 0.2f)

faust("""
#faust
import("stdfaust.lib");
room = hslider("room", 0.5, 0, 1, 0.01);
process = _,_ : re.zita_rev1_stereo(20, 200, 2000, 3.0, 2.0, 48000) : *(room), *(room);
""", input = dry)
```

A program with inputs and no `input` argument hears silence, which looks like a broken effect.

## What it costs

Both halves of Faust run inside the GraalVM runtime Field already uses for `dough.wasm`, and they have
opposite performance profiles. Measured on an M-series Mac, GraalVM CE 25.0.2:

<div class="field-data-table" markdown>

| | Measured | |
|---|---|---|
| **A playing voice, end to end** | 60.4µs per 128-frame block against a 2667µs budget | **44× realtime** |
| **The `compute` call alone** | 7.6µs per block | 353× realtime |
| **The compiler, warm** | ~600ms per compile | ~20× slower than V8 |
| **The compiler, cold** | 13–22s for the first compile in a process | Truffle warm-up |

</div>

The two render figures say different things. Faust's generated code is very fast (7.6µs), and almost
everything else in the 60.4µs is the cost of crossing between the JVM and WebAssembly once per block:
entering the context, and copying the output out. So a voice costs a little over 2% of one core, and a
handful of Faust boxes is unremarkable. A polyphonic bank is cheaper still, because it crosses once for
all its voices.

The compiler is the real cost, and three things blunt it:

* compiles are memoised on a hash of the source, so re-running an unchanged box, or undoing back to a
  program already seen, is free;
* the compiler has its own GraalVM context, never shared with a running DSP, because a 600ms compile
  holding a lock the audio thread wants every 2.67ms would be audible;
* and `FaustEngine.warmInBackground()` pays the cold start on a background thread. Call it from a
  workspace that is going to use Faust and the 13–22s goes by while you are still typing.

## Nothing to install

This is the part that is different from [Sonic Pi](sonic_pi.md), which can only work if Sonic Pi
happens to be installed and whose "owned mode" exists to work around that.

Field vendors the Faust compiler as WebAssembly: `src/jvmMain/resources/faust/`, 5.3MB, LGPL-3.0,
unmodified, with its licence and provenance recorded alongside. WebAssembly is platform-independent,
so those are the *same bytes* on every platform Field runs on:

| | Needs |
|---|---|
| macOS (x86_64, aarch64) | nothing |
| Linux (x86_64, aarch64) | nothing |
| Windows (x86_64) | nothing |

No `faust` on `PATH`, no LLVM (a stock `libfaust` links against it, and is ~100MB *per platform*), no
C++ toolchain, no `faust2*` scripts, no Homebrew or apt or vcpkg step, and no per-platform binary in
`libs/`. A fresh clone can play a Faust box.

The trade is the 5.3MB in the repository and the slow compiler above. That is the right way round for
a live-coding environment: a slow first compile is a warm-up, whereas a missing toolchain is a wall.

!!! note "Not verified on Linux or Windows"
    These are platform-independent bytes on a runtime Field already ships for `dough.wasm`, but the
    measurements above were taken on macOS/aarch64 and nobody has run the smoke test elsewhere.
    `synth.faust.smoke` is the thing to run there; it needs no audio hardware and no GPU.

## Reference

| | |
|---|---|
| `Box.faust(source, …)` | compile and play a `#faust` region; returns a `FaustLaunch` |
| `Box.faustCompile(source)` | compile without playing, to inspect the interface or the diagram |
| `Box.faustControls(launch)` | build the control group by hand, if you passed `controls = false` |
| `Box.faustDiagram(program)` | show the block diagram by hand |
| `Box.faustWaveform(program)` | render offline and plot it — needs no audio device |
| `Box.faustPoly(source, voices)` | compile and play polyphonically; returns a `FaustPolyLaunch` |
| `FaustPolyLaunch.play(pattern, …)` | feed it from a `Pat` pattern, with `automate` for shared controls |
| `FaustPolyLaunch.chord(midis)` | strike a chord from code, every note on the same frame |
| `Box.showFaustPattern(source, pattern)` | render a pattern offline into a player and a waveform |
| `faustPatternRender(…)` | the same, as data |
| `faustParamAutomation(values, control, …)` | a numeric pattern as shared-parameter changes |
| `faustOnsets` / `faustSilentGaps` | attack frames, and the check that there *are* attacks |
| `faustFundamentalHz` / `faustSpectrumPeaks` | pitch by autocorrelation; partials by FFT |
| `FaustLaunch["label"] = v` | set a control by label, address or qualified name |
| `FaustLaunch.press(label)` | momentary trigger for a `button` |
| `FaustLaunch.meter(label)` | read a `bargraph` |
| `FaustEngine.compile(name, src)` | the compiler directly: wasm bytes plus metadata |
| `FaustEngine.blockDiagram(…)` | the SVG set for a program |
| `FaustEngine.warmInBackground()` | pay the cold start early |
| `FaustEngine.libfaustVersion()` | which compiler is actually loaded |

Five tests cover this, and they assert the vendored compiler's behaviour as well as Field's, so a
version bump that changes anything cannot pass unnoticed:

| | |
|---|---|
| `synth.faust.smoke` | compile, UI metadata, diagnostics, render, parameter writes, meters, mono fan-out, effects (the input path), block diagram, render speed |
| `faust.editor-extension.workout` | the shipped `faust.js` under node against the shipped vocabulary — regions, tokenizer, lexical diagnostics, qualified completion, the line arithmetic from both ends |
| `faust.box.controls` | the compiler reached from inside a real box, generated controls, offline render into an output area |
| `faust.box.compile-error` | a bad program's line survives the trip, and the compiler recovers afterwards |
| `synth.faust.poly` | the polyphonic path — control discovery and its refusal, onset timing, pitch, the spectral polyphony check, patterned parameters, voice allocation and stealing, audio-thread allocation, real-time budgets |

What none of them covers is the launch itself: a box staying lit while sound comes out of a speaker.
That is the generic audio return-value path, and it needs an audio device.
