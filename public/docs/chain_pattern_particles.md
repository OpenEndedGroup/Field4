# A pattern that moves particles

Four subsystems, and a rhythm written as `pat("bd*4, ~ 52 ~ 52, hh*16")` ends up deciding the push
constants of a GPU compute shader. The pattern language schedules; Field's audio seam turns the
schedule into samples; four band-passes turn the samples into four numbers; the renderer hands those
numbers to a shader that moves 24,000 particles.

One link in that chain did not exist. [Making a sound, every path there
is](sound_paths.md) has always said why: there is exactly one signature everything realtime meets
at, `render(outL, outR, numFrames, ctx)`, and a pattern could not reach it. A pattern has to be
*queried* over an arc, and there is no "next 128 frames" to ask one for.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_pattern_particles/rings.png" capture="native-window" native-wait-frames="3" wait-output=true wait-output-kind="all" wait-output-contains="particles drawn in four rings" embed=true hidden=true timeout="240" name="chain-pattern-rings" alt="Four concentric rings of particles on black, coloured blue, orange, green and amber from the centre out. The blue innermost ring has bloomed into a thick disc while the other three are thin, because the kick band was loud in the frame that was captured" macro-file="../../dev_docs/agents/transcripts/pattern_particles.yaml" capture-box="pp_draw"}
// Built and launched from dev_docs/agents/transcripts/pattern_particles.yaml.
```

Each ring follows one band, in the same four colours the plot further down uses: the innermost is
70 Hz and the outermost 8 kHz. In the frame above the kick had just landed, so the innermost ring has
bloomed into a disc while the hats hold the outermost one thin. It is one frame of something moving,
and that plot is the signal it is following.

## `patSynth`, the scheduler

Three parts, split by where they run:

| Component | where it runs | what it does |
| --- | --- | --- |
| `PatSynthFeeder` | a daemon thread | queries the pattern a cycle at a time and turns events into notes stamped with the absolute frame they start on |
| `PatSynthBank` | the audio thread | starts a note on the frame it was given, and synthesises |
| `PatSynthLaunch` | neither | owns both, and closes both |

Querying a pattern allocates, and a cycle of a large composed pattern is not a 2.67 ms job, so the
query cannot be on the audio thread. It does not have to be. What the query produces is *frames*, and
being 400 ms early is the same sound as being exactly on time, so the audio thread is left with an
integer comparison. `DirtSamplePlayer` and `FaustPatternFeeder` divide the work the same way; this is
the third of them and the first for Field's own voices.

Playing it is one call, and returning it from the box is what ties the sound to the box:

```diffstatic {data-lang=kotlin}
patSynth(pat("bd ~ sd ~, hh*8"), cps = 2.0)
```

Stopping the box stops the voices and the feeder thread. `PatSynthLaunch` is also a
`StereoRenderable`, so it goes into a larger graph wherever a `Signal` would.

## Every note starts on its own frame

A scheduler that started each note at the top of whichever block it fell in would sound fine, pass
every level and onset check, and be wrong. The symptom is that its output changes when the engine's
buffer size does, so that is what is asserted: `synth.pat.seam` renders three bars at 64, 128, 512
and 1024 frames per block and compares the four buffers **bit for bit**, and separately compares the
frame each onset lands on.

The mixing loop runs samples on the outside and voices on the inside, and that is what makes the
property exact rather than close. The other way round, every note due in a block starts before any
voice in that block has finished, so which of the sixteen slots a note lands in moves with the block
boundaries, and so does the order its samples are summed in. Float addition is not associative, and
one ULP is what that costs. Deciding starts and ends per sample removes the dependency instead of
shrinking it, for one branch per voice per sample.

The same test also puts `patRenderStereo` and `patSynthRenderStereo` on the same bar and compares
them. They are not bit-identical, because the offline renderer works in seconds and truncates where
this one works in frames and rounds, so a note can differ by a frame at each end.

## What it sounds like

Three bars, each one produced by driving a real `PatSynthBank` through a `SynthContext` block by
block, the way the engine does. These are recordings of the realtime path rather than a second
implementation of it.

The beat the rest of the sound documentation starts from:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_pattern_particles/seam-beat.wav" audio-spec="seam-beat" audio-renderer="afield.four.synth.PatSynthDocs" embed=true hidden=true name="chain-pattern-beat-audio"}
// Offline-rendered by PatSynthDocs.renderWav("seam-beat").
```

Sixteen hats against twelve triangle notes, at a tempo where a twelfth of a cycle is 2666.67 frames,
so almost nothing lands on a block boundary at any buffer size:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_pattern_particles/seam-triplets.wav" audio-spec="seam-triplets" audio-renderer="afield.four.synth.PatSynthDocs" embed=true hidden=true name="chain-pattern-triplets-audio"}
// Offline-rendered by PatSynthDocs.renderWav("seam-triplets").
```

And the bar the particles are dancing to, which is a kick, a mid-register square stab on the
offbeats, and sixteen hats:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_pattern_particles/seam-bands.wav" audio-spec="seam-bands" audio-renderer="afield.four.synth.PatSynthDocs" embed=true hidden=true name="chain-pattern-bands-audio"}
// Offline-rendered by PatSynthDocs.renderWav("seam-bands").
```

## The four numbers the shader reads

`BandSplit` pushes a window of samples through a bank of `Biquad` band-passes and returns the RMS of
each. Nothing about it is new DSP: it is the same `Biquad` a box would put in a signal chain, with
its input pointed at a buffer instead of at an oscillator.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_pattern_particles/bands.png" alt="A line plot titled 'four bands of one bar, the numbers the shader reads', four traces over two seconds against a legend of 70 Hz, 340 Hz, 1.6 kHz and 8 kHz. The 70 Hz trace spikes sixteen times, to about 0.32; the 340 Hz trace spikes with it at about two thirds the height; the 1.6 kHz and 8 kHz traces stay busier and flatter, hovering around 0.05 and 0.1" wait="8" capture="box" open-outputs="true" w="1180" h="360" padding="18" timeout="120" name="chain-pattern-bands" embed=true}
:::static
import afield.four.synth.PatSynthDocs
import afield.four.synth.logBands

// The same scene the .wav above is, and the same scene the GPU figure plays.
val scene = PatSynthDocs.SCENES.first { it.name == "seam-bands" }
val audio = scene.render()

val split = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = PatSynthDocs.SR)
val series = PatSynthDocs.bandSeries(audio, split, windowFrames = 2048, hopFrames = 256)

:::fold four bands against time
val seconds = series.indices.map { it * 256.0 / PatSynthDocs.SR }
showSignals(
    "70 Hz" to series.map { it[0] },
    "340 Hz" to series.map { it[1] },
    "1.6 kHz" to series.map { it[2] },
    "8 kHz" to series.map { it[3] },
    x = seconds,
    title = "four bands of one bar, the numbers the shader reads",
    xTitle = "seconds",
    channel = "bands",
)
:::endfold
```

Sixteen kicks in two seconds in the lowest band, which is `bd*4` at two cycles per second. The 340 Hz
band spikes with them rather than with the offbeat stabs, because a kick's attack is broadband and
sixteen kicks are louder in that band than four stabs. The top two bands stay busy and comparatively
flat, because sixteen hats a cycle never leave a gap. That difference is the whole reason the rings
look different from each other.

??? aside "Take band levels from the tap"

    Take your band levels from `PatSynthLaunch.tap`, which is a snapshot the audio path published.
    Do not render the synth graph a second time to get a display value: `afield.four.synth`'s nodes
    are audio-thread-owned, and many of them carry mutable DSP state past the per-cycle `Signal`
    cache, so a second render from another thread is how a modulation oscillator ends up running at
    double speed. `BandSplit` follows the rule: its band-passes and their input buffer are its own,
    and all it reads is the tap.

## Into the push constants

The compute shader declares four floats beside its two buffer references:

```diffstatic {data-lang=glsl}
layout(push_constant) uniform dat {
    Seeds seeds;      // written once: one random phase and radius per particle
    Out   outData;    // this frame's positions, read by the draw box
    float time;
    float band0;      // 70 Hz    — the kick
    float band1;      // 340 Hz
    float band2;      // 1.6 kHz  — the square stab's harmonics
    float band3;      // 8 kHz    — the hats
    uint  count;
} pc;
```

and the Kotlin binds each one to a lambda. The renderer evaluates them once per frame, so the shader
sees this frame's analysis and nothing is copied by hand:

```diffstatic {data-lang=kotlin}
step.band0 = { (bandEnergy.getOrElse(0) { 0.0 } * 2.4).f }
step.band1 = { (bandEnergy.getOrElse(1) { 0.0 } * 2.4).f }
step.band2 = { (bandEnergy.getOrElse(2) { 0.0 } * 2.4).f }
step.band3 = { (bandEnergy.getOrElse(3) { 0.0 } * 2.4).f }
```

`bandEnergy` is an inherited property, declared with `prop<DoubleArray>(bandEnergy)` in the audio box
and read by name in the compute box below it. So the box graph is what says which shader is listening
to which pattern: `pp_renderer → pp_audio → pp_compute → pp_draw`, and moving the compute box out from
under the audio box is how you disconnect them.

In the shader each ring's radius rises a little with its band and its *thickness* rises a lot. A ring
of 6,000 particles all at one radius reads as a drawn circle rather than as particles, which is the
wrong picture of what is there.

```diffstatic {data-lang=glsl}
float base = 0.14 + 0.17 * float(ring);
float thickness = 0.012 + 0.10 * energy;
float radius = base * (1.0 + 0.55 * energy) + thickness * (seed.y - 0.5) * 2.0;
```

??? aside "Two things the figure above is not"

    **It is a still of something moving.** Field's backdoor can record the browser window, the
    editor and a box, and it cannot record a native `afield.four` window; `native-screenshot` is
    what there is. So the page carries one frame plus the band plot rather than a clip, and the frame
    that got captured happens to be one where the kick was loud.

    **Nothing is audible while it is being captured.** The audio box calls
    `PatSynthLaunch.advanceOffline` once per rendered frame instead of returning the launch for the
    engine to pull, so the analysis has samples on a machine with no output device and the figure
    comes out the same everywhere. Same bank, same feeder, same scheduler; what changes is where the
    samples go. Doing both at once would advance every voice twice, which is the same hazard the
    callout above describes, and `FieldAudioEngine.outputActive` is how a box decides which it is
    doing.

## Where each piece is documented

* [Patterns & Rhythm](pattern_language.md) for the event model, the mini-notation and the operators.
* [Making a Sound, Every Path There Is](sound_paths.md) for the seam itself, the other sixteen
  paths, and which of them meet it.
* [DoughDSP Sound Examples](dough_dsp_sound.md) for the filters, envelopes and oscillators a voice
  is built from, and [Synthesis](reference/synthesis.md) plus [Patterns](reference/patterns.md) for
  the declarations.
* [afield.four Graphics](afield_four_graphics.md) for compute modules, buffers and push constants,
  and [GPU State](gpu_state.md) for what changes when a shader's input is its own last output.
* [A cellular automaton you can hear](chain_ca_sound.md) is the neighbouring chain in the other
  direction: a discrete dynamical system into notation, and notation into sound.
