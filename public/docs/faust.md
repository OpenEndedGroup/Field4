# Faust

[Faust](https://faust.grame.fr) is a functional language for signal processing: you write what the
signal *is*, and the compiler turns it into efficient code. Field embeds that compiler itself. A
`#faust` region inside an ordinary Kotlin string is compiled, instantiated and playing through
Field's audio engine, with the sliders it declares showing up as real Field controls.

Nothing is installed to make this work. Not `faust`, not LLVM, not a C++ toolchain. See
[Nothing to install](#nothing-to-install) for why, and what it costs.

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
while the processor runs, and stopping the box stops the sound *and* releases the DSP: the same path
any other Field audio launch takes.

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
  are right, but a log frequency slider moves linearly. Field's slider has no law parameter yet; the
  metadata is parsed and available on `FaustControl.scale` for when it does.

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

Note the second pattern. `Pat` is a rhythm of *anything*: the filter is patterned exactly as the notes
are, and both go through the same event timeline.

### The notes land on the sample, not on the block

A 128-frame block at 48 kHz is 2.67 ms. Quantising a note onset to it is audible: it is the difference
between a groove and a grid. So `FaustVoiceBank.render` enters the GraalVM context **once** and then walks
the block in chunks that end where the next event begins: apply the writes due at this exact frame,
`compute` up to the next event, repeat.

This is also why [`FaustDsp.setParam`](#reference)'s queue is *not* used on this path: it drains once per
`render` call, which is block granularity by construction.

Measured offline, on a pattern of seven notes to a cycle so that twelve of fourteen onsets fall *inside* a
block (`48000/7 = 6857`, and `6857 % 128 = 105`):

| | Frames | ms |
|---|---|---|
| median onset error | 1 | 0.021 |
| jitter (spread about the median) | ±7 | 0.15 |
| worst absolute error | 8 | 0.17 |
| **what a per-block parameter write would give** | **up to 110** | **2.3** |

None of that spread is the scheduler. During a gap this program's output is **exactly `0f`** (the envelope
reaches zero and multiplies the oscillator away). That makes the first non-zero sample of a note an exact
fact about the render, with no threshold in it, and it **is** the notated frame for 13 of 13 checkable
notes, worst 0 frames.

The 0–8 spread in the table is the *onset detector* waiting for the attack ramp to clear its amplitude
threshold, which depends on where the free-running phasor happened to be when the envelope opened. The two
numbers answer different questions: the scheduler is exact to the sample, and an onset detector on a
bandlimited sawtooth resolves an attack to within 8.

A patterned *parameter* shows it from the other side, with no attack ramp in the way:
`pat("0 0 0 1 1 1 1")` on a `level` control rises at frame 20571 (91 frames into a block) and is measured
at frame **20571, error 0**.

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

### Voice allocation, and what it does when it runs out

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

#### A steal clicks, and by how much

Stealing a voice clicks in most polyphonic synths, and here the click is measured. The statistic is the
largest sample-to-sample delta across the steal, normalised by the local peak, against the same statistic
in the same note once it has settled:

| `stealReleaseFrames` | Normalised step across a steal | Stolen note is |
|---|---|---|
| `1` (the default) | **2.71×** the settled step | on time |
| `1440` (30 ms, longer than this program's release) | **0.04×** | 30 ms late |

A normalised step near 1.0 means the largest single-sample jump is as big as the whole local peak, which is
not a slope: it is the outgoing note's amplitude vanishing in one sample. The cause is that Faust's
`en.adsr` **resets to zero** on a trigger rather than continuing from the level it was at, so the one-sample
gate dip that gives the new note its rising edge drops the envelope to 0 before the attack starts.

And the gate dip is not optional. Writing `freq` to a voice whose gate is already up does *not* restart the
envelope, because Faust triggers on a rising edge. Without the dip a steal writes the new pitch into a voice
that never re-attacks: the stolen voice glides in at the old envelope level and no note is heard. With the
dip, the envelope's local peak rises 1.36–1.39× across a steal; without it, 1.00×.

`synth.faust.poly` asserts both ends of the knob, and the default prefers the note being on time.
Stealing is an overload condition either way.

#### `render` is not allocation-free

An allocation in an audio callback is a GC pause in the audio path (a dropout), and it is invisible to a
µs-per-block average. Measured with `getCurrentThreadAllocatedBytes` over 4000 blocks:

| | Bytes per 128-frame block |
|---|---|
| `FaustVoiceBank`, 8 voices, steady state | 7386 (**923 per voice**) |
| `FaustVoiceBank`, **1 voice** — the like-for-like comparison | 984 |
| `FaustDsp`, one voice — the single-voice path above | 1075 |

So one voice of the bank costs **0.92×** what one `FaustDsp` costs, doing the same DSP and paying the same
once-per-block context enter and queue drain. Fitting the two bank measurements as a line gives **915 bytes
per voice** and a fixed **69 bytes per block**, which places the source: it is `Value.readBuffer`, the one
bulk copy per channel per block that gets the samples out of WebAssembly, and it is in both paths.
Both bank measurements are held to a bound by `synth.faust.poly`.

The `voices = 1` row is here because the obvious comparison is wrong in a way that flatters this code.
Dividing an 8-voice block by 8 spreads the bank's fixed cost across eight voices while `FaustDsp` pays its
own once, for one. Measuring 1 and 8 voices in the *same run* and quoting the slope and intercept is what
makes the claim checkable; two figures from different runs, solved as a pair, will happily imply a negative
fixed cost per block.

!!! warning "Garbage on the audio thread"
    ~0.9 KB per voice per block is real GC pressure, and removing it means
    changing how samples leave WebAssembly for *both* the monophonic and polyphonic paths: a
    `ByteBuffer`-backed view rather than a copying `readBuffer`, if the polyglot API allows one. That is the
    next thing to do here.

### Rendering a pattern offline

`faustPatternRender` renders a pattern through a bank with no audio device and no window, in 128-frame
blocks, and through the *same* `render` the audio thread calls. What is measured is what would be heard.
The onset table above and the spectral proof below are measured through it, by `synth.faust.poly`.
`Box.showFaustPattern` is the one-liner that puts a player and a waveform in a box's output.

Three measurement helpers come with it:

* `faustOnsets` — the frame of every attack. A burst is a run of samples that is not quiet, allowing
  quiet stretches shorter than `minGapMs`; the onset is the burst's first sample.
* `faustSilentGaps` — **run this before believing any onset count.** A pattern rendered at `sustain = 1.0`
  is a continuous tone with no attacks at all, and an onset detector pointed at one will report envelope
  ripple as notes. `FAUST_PATTERN_SUSTAIN` is 0.9 rather than 1.0 for exactly this reason.
* `faustSpectrumPeaks` — the only thing here that can tell polyphony from monophony. Counting attacks
  cannot: a single voice stealing itself produces the same number of them.

??? aside "The assertion that proves polyphony"
    `pat("[c4,e4,g4]")` is one chord (three events on the same arc, three notes on the same frame)
    whose fundamentals are 130.81, 164.81 and 196.00 Hz. Nothing else can reach the band 100–230 Hz,
    because the lowest second harmonic in the chord is 261.6 Hz; every peak there is somebody's
    fundamental.
    Rendered at four voices it contains **three** (130.0, 166.2, 196.5 Hz; worst error 0.86 %, all within
    a factor of 1.14 in level). The identical render at `voices = 1` contains **one**, at 196.5 Hz, and
    reports `steals == 2`. A monophonic path cannot produce the first result however it is driven.

### What eight voices cost

`FaustDsp` gives each voice its own GraalVM context, and its KDoc records that the per-block
`Context.enter` dominates what is left after `compute`, which is the argument for hosting every voice of
a bank in **one** context. Measured on an M-series Mac, GraalVM CE 25.0.2, every voice sounding, against a
2667 µs budget for a 128-frame block:

| Voices | One context | N contexts | Ratio | One context |
|---|---|---|---|---|
| 1 | 2.1 µs | 2.5 µs | 1.20× | 1295× realtime |
| 2 | 4.2 µs | 3.8 µs | 0.91× | 640× realtime |
| 4 | 8.2 µs | 11.0 µs | 1.34× | 323× realtime |
| 8 | **16.9 µs** | 22.6 µs | 1.34× | **158× realtime** |
| 16 | 32.7 µs | 29.7 µs | 0.91× | 82× realtime |

Each figure is the **median of five timed windows** rather than the mean of one. `render` allocates ~7.4 KB
per block at eight voices — ~15 MB over a 2000-block window — and an ordinary young collection inside that
moves the whole average with nothing in the number to say so: a single-window mean reports **238 µs** for
eight voices in the same output that measures 13.4 µs for the same configuration a few lines later. The
median leaves the shape legible, and 2.1, 4.2, 8.2, 16.9, 32.7 is **linear in voices**, which is what a bank
of independent DSPs should be.

At steady state the arrangement barely matters: once Truffle has compiled everything the per-block
`Context.enter` is a few hundred nanoseconds, and one context and N contexts are within noise of each other
either way round. Two measurements still make one context the right arrangement:

* **construction** — 6 ms for sixteen voices in one context against 46 ms for sixteen contexts (7.8×),
  because N contexts each compile the same WebAssembly module again;
* **sub-block scheduling**, which is the stronger of the two. A block split at eight event boundaries costs
  33.0 µs against 15.0 µs undisturbed (2.2×), and is still 81× realtime with a note starting every 0.33 ms,
  which is orders of magnitude denser than music. The N-context arrangement would pay N context entries per
  *chunk* rather than per block, and `FaustDsp`'s per-block parameter queue could not express the split at
  all.

These figures are much cheaper than the 60.4 µs quoted for a single `FaustDsp` block below, and both are
correct: that one is measured after 500 warm-up blocks, these after several seconds of real rendering, by
which point Truffle has compiled the wasm properly. The first bank built in a process really does cost
118 µs a block until it warms.

### The audio thread takes no lock

`FaustDsp`'s KDoc states the rule: *no lock on the audio thread*. A callback that can block behind another
thread has a worst case measured in that thread's scheduling rather than in its own work, and the symptom is a
dropout, not a slow number.

The bank keeps it. Its two ordered queues (notes and parameter changes) are **owned by the audio thread and
touched by nothing else**; `schedule` and `automate` hand work over through lock-free
`ConcurrentLinkedQueue`s, which `render` drains once at the top of each block. Producers pay the node
allocation on their own thread, where it is harmless.

The shape that does *not* work is one priority queue under a mutex taken by both sides. `render` acquires
such a lock several times **per chunk**, and a chunk is as short as the gap between two notes, while a
feeder calling `schedule` with a cycle of notes holds it across one heap insert per note, with the audio
thread waiting behind the whole batch. Handing over instead of locking costs one thing: work handed over
*during* a `render` call is seen by the next block, at most 2.67 ms later. That is already the modelled
case, since notes are scheduled ahead of their frame and one that arrives late is played immediately and
counted in `lateNotes`. For the same reason `clearSchedule()` takes effect within a block rather than
instantly.

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
The links between them are file references, so drilling down is not wired up yet.

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

| | Measured | |
|---|---|---|
| **A playing voice, end to end** | 60.4µs per 128-frame block against a 2667µs budget | **44× realtime** |
| **The `compute` call alone** | 7.6µs per block | 353× realtime |
| **The compiler, warm** | ~600ms per compile | ~20× slower than V8 |
| **The compiler, cold** | 13–22s for the first compile in a process | Truffle warm-up |

The two render figures say different things. Faust's generated code is very fast (7.6µs), and almost
everything else in the 60.4µs is the cost of crossing between the JVM and WebAssembly once per block:
entering the context, and copying the output out. How often that crossing happens is the whole cost, and
`FaustDsp`'s KDoc records three measured arrangements of it:

* a call per *sample* to copy the output: **148µs** a block (18× realtime),
* one bulk buffer read per *channel* instead: **94µs** (28×),
* resolving the JS entry points once instead of per block: **60µs** (44×).

So a voice costs a little over 2% of one core, and a handful of Faust boxes is unremarkable. If that
ever becomes the limit, the next thing to remove is the per-block context entry, not anything in Faust.

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
| `synth.faust.smoke` | compile, UI metadata, diagnostics, render, parameter writes, meters, mono fan-out, effects (the input path), block diagram, and the render speed |
| `faust.editor-extension.workout` | the shipped `faust.js` under node against the shipped vocabulary — regions, tokenizer, lexical diagnostics, qualified completion, the line arithmetic from both ends |
| `faust.box.controls` | the compiler reached from inside a real box, generated controls, offline render into an output area |
| `faust.box.compile-error` | a bad program's line survives the trip, and the compiler recovers afterwards |
| `synth.faust.poly` | the polyphonic path, 99 deterministic checks: control discovery and its refusal, the onset detector's own sanity, onset timing in samples, pitch in cents, the polyphony-against-monophony spectral proof, a patterned parameter, voice allocation, whether a steal clicks (it does, measurably, and the knob that stops it), how many bytes a block allocates on the audio thread, and absolute real-time budget checks; relative wall-clock ratios remain diagnostic output |

What none of them covers is the launch itself: a box staying lit while sound comes out of a speaker.
That is the generic audio return-value path, and it needs an audio device.
